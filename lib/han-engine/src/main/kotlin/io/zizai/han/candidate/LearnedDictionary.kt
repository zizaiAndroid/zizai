/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.candidate

import io.zizai.han.parse.EdgeKind
import io.zizai.han.parse.SyllableLattice
import io.zizai.han.reading.Mandarin

/**
 * What the user typed, learned on commit (docs/IMPLEMENTATION_PLAN.md P2-02), and what they blocked.
 *
 * Entries are keyed by text and syllables, so a learned word also comes up for its initials (`zg` for
 * 中国). A word chosen from the lexicon shows here once it was chosen twice; a phrase the user assembled
 * from several picks (造词) shows from the first time. Nothing here knows about privacy: the session
 * doesn't call [learn] where learning is off.
 */
class LearnedDictionary(private val now: () -> Long = System::currentTimeMillis) : UserDictionary {
    class Entry(val text: String, val syllables: IntArray, var count: Int, var lastUsed: Long, var assembled: Boolean)

    private val entries = HashMap<String, Entry>()
    private val byFirstSyllable = HashMap<Int, MutableList<Entry>>()
    private val blocked = HashSet<String>()

    /** Called after each change, so the owner can persist. */
    var onChanged: (() -> Unit)? = null

    val size: Int get() = entries.size

    override fun phrases(lattice: SyllableLattice): List<Candidate> {
        val n = lattice.length
        val first = lattice.edgesFrom(CandidateEngine.skipApostrophes(lattice.input, 0))
        if (first.isEmpty()) return emptyList()
        val seen = HashSet<Entry>()
        val out = ArrayList<Entry>()
        for (edge in first) {
            if (edge.kind == EdgeKind.RAW) continue
            for (s in edge.first..edge.last) {
                for (e in byFirstSyllable[s].orEmpty()) {
                    if (e in seen || !(e.assembled || e.count >= SHOW_AFTER) || e.text in blocked) continue
                    seen += e
                    if (matchesWholeInput(lattice, e.syllables)) out += e
                }
            }
        }
        out.sortWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
        return out.map { Candidate(it.text, n, CandidateKind.USER, USER_SCORE + it.count) }
    }

    override fun isBlocked(text: String): Boolean = text in blocked

    override fun learn(text: String, syllables: IntArray, assembled: Boolean) {
        if (text.isEmpty() || syllables.size != text.codePointCount(0, text.length)) return
        val key = key(text, syllables)
        val e = entries[key] ?: Entry(text, syllables, 0, 0, false).also {
            entries[key] = it
            byFirstSyllable.getOrPut(syllables[0]) { ArrayList() } += it
        }
        e.count++
        e.lastUsed = now()
        e.assembled = e.assembled || assembled
        blocked -= text
        onChanged?.invoke()
    }

    override fun block(text: String) {
        blocked += text
        onChanged?.invoke()
    }

    /**
     * The user's own words and phrases, most used first: assembled phrases and words chosen at least
     * twice, two characters or more, not blocked. For dictation's hot-word glossary (P2-07).
     */
    fun topPhrases(n: Int): List<String> = entries.values
        .filter { it.syllables.size >= 2 && (it.assembled || it.count >= SHOW_AFTER) && it.text !in blocked }
        .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed }.thenBy { it.text })
        .map { it.text }
        .distinct()
        .take(n)

    fun clear() {
        entries.clear()
        byFirstSyllable.clear()
        blocked.clear()
        onChanged?.invoke()
    }

    /**
     * One line per entry, `text TAB spellings TAB count TAB lastUsed TAB assembled`, and one per blocked
     * text, `!blocked TAB text`. Spellings rather than ids, so a changed syllable table doesn't scramble it.
     */
    fun serialize(): String = buildString {
        for (e in entries.values.sortedWith(compareBy({ it.text }, { it.syllables.joinToString() }))) {
            append(e.text).append('\t')
            append(e.syllables.joinToString(" ") { Mandarin.syllables.spellingOf(it) }).append('\t')
            append(e.count).append('\t').append(e.lastUsed).append('\t').append(if (e.assembled) 1 else 0).append('\n')
        }
        for (b in blocked.sorted()) append("!blocked\t").append(b).append('\n')
    }

    /** Loads [text] from [serialize]; malformed lines are skipped. Replaces the current contents. */
    fun load(text: String) {
        entries.clear()
        byFirstSyllable.clear()
        blocked.clear()
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            if (f.size == 2 && f[0] == "!blocked") {
                blocked += f[1]
                continue
            }
            if (f.size != 5) continue
            val ids = f[1].split(' ').map { Mandarin.syllables.idOf(it) }
            if (ids.any { it < 0 }) continue
            val syllables = ids.toIntArray()
            if (syllables.size != f[0].codePointCount(0, f[0].length)) continue
            val e = Entry(f[0], syllables, f[2].toIntOrNull() ?: continue, f[3].toLongOrNull() ?: 0, f[4] == "1")
            entries[key(e.text, syllables)] = e
            byFirstSyllable.getOrPut(syllables[0]) { ArrayList() } += e
        }
    }

    /** True if [syllables] are, in order, one edge each across the whole input (full syllable or initial). */
    private fun matchesWholeInput(lattice: SyllableLattice, syllables: IntArray): Boolean {
        val input = lattice.input
        fun walk(pos: Int, i: Int): Boolean {
            val p = CandidateEngine.skipApostrophes(input, pos)
            if (i == syllables.size) return p == input.length
            return lattice.edgesFrom(p).any { e ->
                e.kind != EdgeKind.RAW && syllables[i] in e.first..e.last && walk(e.end, i + 1)
            }
        }
        return walk(0, 0)
    }

    private fun key(text: String, syllables: IntArray) = text + "\u0000" + syllables.joinToString(",")

    private companion object {
        /** A lexicon word the user chose this many times comes up as theirs. */
        const val SHOW_AFTER = 2

        /** Ahead of every lexicon score, which tops out near 16. */
        const val USER_SCORE = 100.0
    }
}
