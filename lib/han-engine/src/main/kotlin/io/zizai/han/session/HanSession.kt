/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.session

import io.zizai.han.candidate.Candidate
import io.zizai.han.candidate.CandidateEngine
import io.zizai.han.candidate.CandidateKind
import io.zizai.han.candidate.CandidatePager
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.parse.EdgeKind
import io.zizai.han.parse.PinyinParser
import io.zizai.han.parse.SyllableLattice
import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Pinyin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The IME-owned, synchronous Chinese composition session (ADR-2, docs/ARCHITECTURE.md §6.2). Both the
 * hardware keyboard and the touch keyboard drive the same session.
 */
interface HanSession {
    val ui: StateFlow<HanUiState>
    val isActive: Boolean get() = ui.value.mode != SessionMode.IDLE

    /** Synchronous; must stay under 2 ms at the 95th percentile. */
    fun onKey(stroke: KeyStroke): KeyResult
    fun onCandidateTapped(page: Int, index: Int): KeyResult

    /** The first [count] candidates across pages, for the touch keyboard's scrolling strip. */
    fun peekCandidates(count: Int): List<Candidate>
    fun startReconversion(target: ReconversionTarget): KeyResult

    /** Commits the composition as typed (Shift tap, switching to English). */
    fun commitRaw(): KeyResult
    fun reset(reason: ResetReason)
}

class DefaultHanSession(
    private val lexicon: LexiconReader,
    private val engine: CandidateEngine = CandidateEngine(lexicon),
    private val settings: () -> HanSettings = { HanSettings() },
    private val editor: EditorContext = EditorContext.None,
) : HanSession {
    private val state = MutableStateFlow(HanUiState.Idle)
    override val ui: StateFlow<HanUiState> = state.asStateFlow()

    private val punctuation = Punctuation { settings().punctuationStyle }

    private var mode = SessionMode.IDLE

    // Composition
    private val input = StringBuilder()
    private val selections = ArrayList<Selection>()
    private var lattice: SyllableLattice? = null
    private var pager: CandidatePager? = null
    private var page = 0
    private var highlight = 0
    private var toneFilter = 0
    private var grid: List<GridRow> = emptyList()
    private var gridEnd = 0
    private var gridRow = 0
    private var gridColumn = 0

    // Reconversion
    private var recon: Reconversion? = null

    private class Selection(val text: String, val consumed: String)
    private class Reconversion(val target: ReconversionTarget, val syllables: List<Int>, var options: List<String>, var index: Int)

    override fun onKey(stroke: KeyStroke): KeyResult = when (mode) {
        SessionMode.IDLE -> onIdleKey(stroke)
        SessionMode.COMPOSING -> onComposingKey(stroke)
        SessionMode.EXPANDED -> onExpandedKey(stroke)
        SessionMode.RECONVERTING -> onReconvertingKey(stroke)
    }

    override fun onCandidateTapped(page: Int, index: Int): KeyResult {
        if (mode == SessionMode.RECONVERTING) return pickReconversion(page * pageSize + index)
        val c = pager?.page(page)?.getOrNull(index) ?: return handled()
        return select(c)
    }

    override fun peekCandidates(count: Int): List<Candidate> {
        // While flipping: the options from the first, so a strip index is an option index.
        recon?.let { r -> return r.options.take(count).map { Candidate(it, 0, CandidateKind.CHAR, 0.0) } }
        val p = pager ?: return emptyList()
        val out = ArrayList<Candidate>(count)
        var i = 0
        while (out.size < count) {
            val pg = p.page(i++)
            if (pg.isEmpty()) break
            out += pg
        }
        return out.take(count)
    }

    override fun commitRaw(): KeyResult {
        if (mode == SessionMode.RECONVERTING) return confirmReconversion()
        if (mode == SessionMode.IDLE) return KeyResult.NotHandled
        return commitAll(selections.joinToString("") { it.text } + input)
    }

    override fun reset(reason: ResetReason) {
        clearComposition()
        recon = null
        mode = SessionMode.IDLE
        publish()
    }

    // ---- Idle ----

    private fun onIdleKey(s: KeyStroke): KeyResult {
        if (s.hasCommandModifier) return KeyResult.NotHandled
        return when (s.key) {
            Key.CHAR -> {
                val c = s.codePoint
                when {
                    c in 'a'.code..'z'.code && !s.shift -> {
                        input.append(c.toChar())
                        mode = SessionMode.COMPOSING
                        refresh()
                        handled(preeditEdit())
                    }
                    settings().fullWidthPunctuation && c < 128 ->
                        punctuation.convert(c.toChar(), editor.textBeforeCursor(1))
                            ?.let { handled(EditorEdit.Commit(it)) } ?: KeyResult.NotHandled
                    else -> KeyResult.NotHandled
                }
            }
            Key.FLIP -> reconvertBeforeCursor()
            else -> KeyResult.NotHandled
        }
    }

    private fun reconvertBeforeCursor(): KeyResult {
        val before = editor.textBeforeCursor(CONTEXT_UNITS + 2)?.toString() ?: return KeyResult.NotHandled
        val cursor = editor.cursorPosition()
        if (before.isEmpty() || cursor < 0) return KeyResult.NotHandled
        // Code points, not UTF-16 units, so Extension B characters flip whole.
        val cp = Character.codePointBefore(before, before.length)
        val len = Character.charCount(cp)
        val after = editor.textAfterCursor(CONTEXT_UNITS)?.toString().orEmpty()
        return startReconversion(
            ReconversionTarget(String(Character.toChars(cp)), cursor - len, cursor, before.dropLast(len), after),
        )
    }

    // ---- Composing ----

    private fun onComposingKey(s: KeyStroke): KeyResult {
        if (s.hasCommandModifier) {
            return when {
                s.ctrl && s.key == Key.BACKSPACE -> deleteSyllable()
                s.ctrl && s.key == Key.DELETE -> handled() // demote: needs the user dictionary (later)
                else -> KeyResult.NotHandled
            }
        }
        return when (s.key) {
            Key.CHAR -> onComposingChar(s)
            Key.SPACE -> current()?.let(::select) ?: handled()
            Key.ENTER -> commitRaw()
            Key.ESCAPE -> cancel()
            Key.BACKSPACE -> deleteLetter()
            Key.TAB -> cycleTone(if (s.shift) -1 else 1)
            Key.LEFT -> moveHighlight(-1)
            Key.RIGHT -> moveHighlight(1)
            Key.DOWN -> expand()
            Key.PAGE_DOWN -> turnPage(1)
            Key.PAGE_UP -> turnPage(-1)
            else -> handled()
        }
    }

    private fun onComposingChar(s: KeyStroke): KeyResult {
        val c = s.codePoint
        return when {
            c in 'a'.code..'z'.code && !s.shift -> {
                input.append(c.toChar())
                refresh()
                handled(preeditEdit())
            }
            c == '\''.code -> {
                if (input.isNotEmpty() && input.last() != '\'') {
                    input.append('\'')
                    refresh()
                }
                handled(preeditEdit())
            }
            c in '0'.code..'9'.code -> {
                val i = if (c == '0'.code) 9 else c - '1'.code
                pager?.page(page)?.getOrNull(i)?.let(::select) ?: handled()
            }
            c == '='.code -> turnPage(1)
            c == '-'.code -> turnPage(-1)
            c == '['.code || c == ']'.code -> {
                val cand = current() ?: return handled()
                val multi = cand.kind != CandidateKind.CHAR && cand.text.codePointCount(0, cand.text.length) > 1
                select(if (!multi) cand else if (c == '['.code) cand.firstCharacter() else cand.lastCharacter())
            }
            else -> {
                // Any other printable key commits the conversion, then types as it would when idle.
                val committed = commitBest()
                val then = onIdleKey(s)
                if (then is KeyResult.Handled) handled(committed + then.edits)
                else KeyResult.Handled(committed, passThrough = true)
            }
        }
    }

    private fun select(c: Candidate): KeyResult {
        val n = input.length
        val end = CandidateEngine.skipApostrophes(input.toString(), c.end).coerceAtMost(n)
        selections += Selection(c.text, input.substring(0, end))
        input.delete(0, end)
        if (input.isEmpty()) return commitAll(selections.joinToString("") { it.text })
        mode = SessionMode.COMPOSING
        refresh()
        return handled(preeditEdit())
    }

    /** The highlighted candidate's text if it covers the input, else the best sentence, else the letters. */
    private fun commitBest(): List<EditorEdit> {
        val c = current()
        val rest = when {
            c != null && c.end >= input.length -> c.text
            else -> lattice?.let { engine.bestSentence(it, filter())?.text } ?: input.toString()
        }
        val text = selections.joinToString("") { it.text } + rest
        clearComposition()
        mode = SessionMode.IDLE
        publish()
        return listOf(EditorEdit.Commit(text))
    }

    private fun commitAll(text: String): KeyResult {
        clearComposition()
        mode = SessionMode.IDLE
        publish()
        return handled(EditorEdit.Commit(text))
    }

    private fun cancel(): KeyResult {
        clearComposition()
        mode = SessionMode.IDLE
        publish()
        // Clearing the composing text first keeps the last letter from being committed (spike S1).
        return handled(EditorEdit.SetPreedit(""), EditorEdit.FinishComposing)
    }

    private fun deleteLetter(): KeyResult {
        if (input.isNotEmpty()) input.deleteCharAt(input.length - 1)
        while (input.isNotEmpty() && input.last() == '\'') input.deleteCharAt(input.length - 1)
        if (input.isEmpty() && selections.isNotEmpty()) input.insert(0, selections.removeAt(selections.size - 1).consumed)
        if (input.isEmpty()) return cancel()
        refresh()
        return handled(preeditEdit())
    }

    private fun deleteSyllable(): KeyResult {
        val last = lattice?.bestPaths(1)?.firstOrNull()?.edges?.lastOrNull()
        if (last == null || last.start == 0) {
            input.setLength(0)
        } else {
            input.setLength(last.start)
        }
        while (input.isNotEmpty() && input.last() == '\'') input.deleteCharAt(input.length - 1)
        if (input.isEmpty() && selections.isNotEmpty()) input.insert(0, selections.removeAt(selections.size - 1).consumed)
        if (input.isEmpty()) return cancel()
        refresh()
        return handled(preeditEdit())
    }

    private fun cycleTone(step: Int): KeyResult {
        toneFilter = (toneFilter + step).mod(Mandarin.maxTone + 1)
        if (mode == SessionMode.EXPANDED) {
            buildGrid()
            publish()
            return handled()
        }
        refresh(keepTone = true)
        return handled(preeditEdit())
    }

    private fun moveHighlight(step: Int): KeyResult {
        val p = pager ?: return handled()
        val size = p.page(page).size
        val h = highlight + step
        when {
            h in 0 until size -> highlight = h
            h >= size && p.hasPage(page + 1) -> { page++; highlight = 0 }
            h < 0 && page > 0 -> { page--; highlight = p.page(page).size - 1 }
        }
        publish()
        return handled()
    }

    private fun turnPage(step: Int): KeyResult {
        val p = pager ?: return handled()
        val next = page + step
        if (next >= 0 && p.hasPage(next)) {
            page = next
            highlight = 0
            publish()
        }
        return handled()
    }

    // ---- Expanded grid ----

    private fun expand(): KeyResult {
        val first = lattice?.bestPaths(1)?.firstOrNull()?.edges?.firstOrNull() ?: return handled()
        if (first.kind == EdgeKind.RAW || first.first != first.last) return handled()
        gridEnd = first.end
        mode = SessionMode.EXPANDED
        gridRow = 0
        gridColumn = 0
        buildGrid()
        publish()
        return handled()
    }

    private fun buildGrid() {
        val first = lattice?.bestPaths(1)?.firstOrNull()?.edges?.firstOrNull() ?: return
        val syllable = first.first
        val rows = (1..Mandarin.maxTone).filter { toneFilter == 0 || it == toneFilter }.map { tone ->
            val chars = ArrayList<String>()
            val start = lexicon.postingStart(syllable)
            for (p in start until start + lexicon.postingCount(syllable)) {
                if (lexicon.postingToneMask(p) and (1 shl tone) == 0) continue
                val cp = lexicon.postingCodePoint(p)
                if (settings().filter.copy(tone = 0).accepts(cp, lexicon.postingToneMask(p), lexicon.postingTier(p), lexicon.postingFlags(p))) {
                    chars += String(Character.toChars(cp))
                }
            }
            GridRow(Pinyin.toneMarked(Mandarin.syllables.spellingOf(syllable), tone), tone, chars)
        }.filter { it.characters.isNotEmpty() }
        grid = rows
        gridRow = gridRow.coerceIn(0, maxOf(0, rows.size - 1))
        gridColumn = gridColumn.coerceIn(0, maxOf(0, (rows.getOrNull(gridRow)?.characters?.size ?: 1) - 1))
    }

    private fun onExpandedKey(s: KeyStroke): KeyResult {
        if (s.hasCommandModifier) return KeyResult.NotHandled
        return when (s.key) {
            Key.ESCAPE -> { mode = SessionMode.COMPOSING; grid = emptyList(); publish(); handled() }
            Key.UP -> { gridRow = (gridRow - 1).coerceAtLeast(0); clampColumn(); publish(); handled() }
            Key.DOWN -> { gridRow = (gridRow + 1).coerceAtMost(maxOf(0, grid.size - 1)); clampColumn(); publish(); handled() }
            Key.LEFT -> { gridColumn = (gridColumn - 1).coerceAtLeast(0); publish(); handled() }
            Key.RIGHT -> { gridColumn++; clampColumn(); publish(); handled() }
            Key.TAB -> cycleTone(if (s.shift) -1 else 1)
            Key.SPACE, Key.ENTER -> {
                val text = grid.getOrNull(gridRow)?.characters?.getOrNull(gridColumn) ?: return handled()
                grid = emptyList()
                select(Candidate(text, gridEnd, CandidateKind.CHAR, 0.0))
            }
            Key.BACKSPACE -> { mode = SessionMode.COMPOSING; grid = emptyList(); deleteLetter() }
            else -> handled()
        }
    }

    private fun clampColumn() {
        gridColumn = gridColumn.coerceIn(0, maxOf(0, (grid.getOrNull(gridRow)?.characters?.size ?: 1) - 1))
    }

    // ---- Reconversion ----

    override fun startReconversion(target: ReconversionTarget): KeyResult {
        if (mode != SessionMode.IDLE) return KeyResult.NotHandled
        val cp = target.text.codePointAt(0)
        val ch = lexicon.charIndex(cp)
        if (ch < 0) return KeyResult.NotHandled
        val own = (0 until lexicon.readingCount(ch)).map { LexiconReader.readingSyllable(lexicon.reading(ch, it)) }
        // The reading the surrounding word gives it comes first: 银行 makes 行 háng, not xíng.
        val syllables = (listOfNotNull(contextSyllable(target)) + own).distinct()
        val r = Reconversion(target, syllables, emptyList(), 0)
        toneFilter = 0
        r.options = reconversionOptions(r)
        if (r.options.size < 2) return KeyResult.NotHandled
        r.index = 1
        recon = r
        mode = SessionMode.RECONVERTING
        publish()
        return handled(EditorEdit.MarkComposing(target.start, target.end), EditorEdit.SetPreedit(r.options[1]))
    }

    /**
     * The original first; then the characters of its main reading that make a word with their
     * neighbours (在见 → 再见), best word first; then every other character sharing one of its readings,
     * best first. Only the main reading is boosted, or a rare one would jump ahead (在 → 才 in 我才).
     */
    private fun reconversionOptions(r: Reconversion): List<String> {
        val all = LinkedHashSet<String>()
        val main = HashSet<String>()
        val filter = settings().filter.copy(tone = toneFilter)
        for (s in r.syllables) {
            val start = lexicon.postingStart(s)
            for (p in start until start + lexicon.postingCount(s)) {
                val cp = lexicon.postingCodePoint(p)
                if (filter.accepts(cp, lexicon.postingToneMask(p), lexicon.postingTier(p), lexicon.postingFlags(p))) {
                    val c = String(Character.toChars(cp))
                    all += c
                    // Only characters common with this reading (they have a frequency for it): Unihan also lists
                    // archaic ones, such as 才 for zài.
                    if (s == r.syllables.first() && lexicon.postingScore(p) > 0) main += c
                }
            }
        }
        val prev = r.target.before.lastCodePointString()
        val next = r.target.after.firstCodePointString()
        val inWords = main.mapNotNull { c ->
            if (c == r.target.text) return@mapNotNull null
            val score = maxOf(wordScore(prev + c, prev.isNotEmpty()), wordScore(c + next, next.isNotEmpty()))
            if (score > 0) c to score else null
        }.sortedByDescending { it.second }.map { it.first }
        return (listOf(r.target.text) + inWords + all).distinct()
    }

    /** The best lexicon score of a word spelled exactly [text], or 0. */
    private fun wordScore(text: String, enabled: Boolean): Int {
        if (!enabled) return 0
        return lexicon.wordsWithText(text).maxOfOrNull { lexicon.wordScore(lexicon.wordByText(it)) } ?: 0
    }

    /** The syllable the best lexicon word around the target gives it, if the target sits in one. */
    private fun contextSyllable(t: ReconversionTarget): Int? {
        val before = t.before.codePointStrings().takeLast(3)
        val after = t.after.codePointStrings().take(3)
        var best: Int? = null
        var bestScore = 0
        for (k in 0..before.size) for (m in 0..after.size) {
            if (k + m == 0 || k + m > 3) continue
            val text = before.takeLast(k).joinToString("") + t.text + after.take(m).joinToString("")
            for (pos in lexicon.wordsWithText(text)) {
                val w = lexicon.wordByText(pos)
                if (lexicon.wordScore(w) > bestScore && lexicon.wordSyllableCount(w) == k + m + 1) {
                    bestScore = lexicon.wordScore(w)
                    best = lexicon.wordSyllable(w, k)
                }
            }
        }
        return best
    }

    private fun String.codePointStrings(): List<String> = codePoints().toArray().map { String(Character.toChars(it)) }

    private fun String.lastCodePointString(): String =
        if (isEmpty()) "" else String(Character.toChars(codePointBefore(length)))

    private fun String.firstCodePointString(): String =
        if (isEmpty()) "" else String(Character.toChars(codePointAt(0)))

    private fun onReconvertingKey(s: KeyStroke): KeyResult {
        val r = recon ?: return KeyResult.NotHandled
        if (s.hasCommandModifier) return confirmReconversion(passThrough = true)
        return when (s.key) {
            Key.FLIP -> flip(r, if (s.shift) -1 else 1)
            Key.DOWN, Key.RIGHT -> flip(r, 1)
            Key.UP, Key.LEFT -> flip(r, -1)
            Key.PAGE_DOWN -> flip(r, pageSize)
            Key.PAGE_UP -> flip(r, -pageSize)
            Key.TAB -> {
                toneFilter = (toneFilter + if (s.shift) -1 else 1).mod(Mandarin.maxTone + 1)
                val current = r.options[r.index]
                r.options = reconversionOptions(r)
                r.index = r.options.indexOf(current).coerceAtLeast(0)
                publish()
                handled(EditorEdit.SetPreedit(r.options[r.index]))
            }
            Key.ENTER, Key.SPACE -> confirmReconversion()
            Key.ESCAPE -> {
                val original = r.target.text
                endReconversion()
                handled(EditorEdit.SetPreedit(original), EditorEdit.FinishComposing)
            }
            Key.CHAR -> {
                val c = s.codePoint
                if (c in '0'.code..'9'.code) {
                    val i = if (c == '0'.code) 9 else c - '1'.code
                    pickReconversion((r.index / pageSize) * pageSize + i)
                } else {
                    // Confirm, then the key types as usual (it may start a new composition).
                    endReconversion()
                    val then = onIdleKey(s)
                    if (then is KeyResult.Handled) handled(listOf(EditorEdit.FinishComposing) + then.edits)
                    else KeyResult.Handled(listOf(EditorEdit.FinishComposing), passThrough = true)
                }
            }
            else -> confirmReconversion(passThrough = true)
        }
    }

    private fun flip(r: Reconversion, step: Int): KeyResult {
        r.index = (r.index + step).mod(r.options.size)
        publish()
        return handled(EditorEdit.SetPreedit(r.options[r.index]))
    }

    private fun pickReconversion(index: Int): KeyResult {
        val r = recon ?: return handled()
        val text = r.options.getOrNull(index) ?: return handled()
        endReconversion()
        return handled(EditorEdit.SetPreedit(text), EditorEdit.FinishComposing)
    }

    private fun confirmReconversion(passThrough: Boolean = false): KeyResult {
        endReconversion()
        return KeyResult.Handled(listOf(EditorEdit.FinishComposing), passThrough)
    }

    private fun endReconversion() {
        recon = null
        toneFilter = 0
        mode = SessionMode.IDLE
        publish()
    }

    // ---- State ----

    private val pageSize get() = settings().pageSize

    private companion object {
        /** UTF-16 units of context read on each side of a flip: three characters, even surrogate pairs. */
        const val CONTEXT_UNITS = 6
    }

    private fun filter() = settings().filter.copy(tone = toneFilter)

    private fun current(): Candidate? = pager?.page(page)?.getOrNull(highlight)

    private fun refresh(keepTone: Boolean = false) {
        if (!keepTone) toneFilter = 0
        val l = PinyinParser.parse(input.toString())
        lattice = l
        pager = CandidatePager(engine.candidates(l, filter()), pageSize)
        page = 0
        highlight = 0
        publish()
    }

    private fun clearComposition() {
        input.setLength(0)
        selections.clear()
        lattice = null
        pager = null
        page = 0
        highlight = 0
        toneFilter = 0
        grid = emptyList()
    }

    private fun preeditText(): String {
        val l = lattice
        val spelled = if (l == null || l.length == 0) "" else l.bestPaths(1).firstOrNull()?.spelling(l.input) ?: l.input
        return selections.joinToString("") { it.text } + spelled
    }

    private fun preeditEdit() = EditorEdit.SetPreedit(preeditText())

    private fun publish() {
        state.value = when (mode) {
            SessionMode.IDLE -> HanUiState.Idle
            SessionMode.RECONVERTING -> {
                val r = recon!!
                val p = r.index / pageSize
                HanUiState(
                    mode = mode,
                    preedit = r.options[r.index],
                    candidates = r.options.drop(p * pageSize).take(pageSize).map { Candidate(it, 0, CandidateKind.CHAR, 0.0) },
                    highlighted = r.index - p * pageSize,
                    page = p,
                    hasPreviousPage = p > 0,
                    hasNextPage = (p + 1) * pageSize < r.options.size,
                    toneFilter = toneFilter,
                )
            }
            else -> {
                val pg = pager
                HanUiState(
                    mode = mode,
                    preedit = preeditText(),
                    candidates = pg?.page(page).orEmpty().toList(),
                    highlighted = highlight,
                    page = page,
                    hasPreviousPage = page > 0,
                    hasNextPage = pg?.hasPage(page + 1) == true,
                    toneFilter = toneFilter,
                    grid = if (mode == SessionMode.EXPANDED) grid else emptyList(),
                    gridRow = gridRow,
                    gridColumn = gridColumn,
                )
            }
        }
    }

    private fun handled(vararg edits: EditorEdit) = KeyResult.Handled(edits.toList())
    private fun handled(edits: List<EditorEdit>) = KeyResult.Handled(edits)
}
