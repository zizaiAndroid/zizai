/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import org.florisboard.lib.snygg.ui.SnyggText

/**
 * The touch candidate strip while Chinese is composing or flipping: up to [ZizaiIme.TOUCH_CANDIDATES]
 * candidates at full size on two lines, no scrolling, each line spread to fill the width, with ◂ and ▸ at
 * the end of the second line for the rest. The Smartbar gives the strip two lines' height while it shows,
 * taking the prompt row's place so the keyboard doesn't change height. The layout reports how many
 * candidates it fitted, so ▸ continues right after the last one.
 */
object HanCandidateStrip {
    private const val LINES = 2
    private val MIN_CELL = 36.dp
    private val CELL_PADDING = 6.dp

    /** Whether the strip should draw [candidates] instead of upstream's row: a composition's candidates. */
    fun handles(candidates: List<SuggestionCandidate>): Boolean =
        candidates.any { it is HanSuggestionCandidate && it.index >= 0 }

    /** Whether the strip is showing now, for the Smartbar's layout. */
    @Composable
    fun rememberActive(): Boolean {
        val nlpManager by LocalContext.current.nlpManager()
        val candidates by nlpManager.activeCandidatesFlow.collectAsState()
        return handles(candidates)
    }

    /** How many Smartbar lines the candidate row takes. */
    @Composable
    fun lines(): Int = if (rememberActive()) LINES else 1

    @Composable
    fun Strip(candidates: List<SuggestionCandidate>, modifier: Modifier = Modifier) {
        val keyboardManager by LocalContext.current.keyboardManager()
        val lineHeight = FlorisImeSizing.smartbarHeight
        val han = candidates.filterIsInstance<HanSuggestionCandidate>()
        val words = han.filter { it.index >= 0 }
        val previous = han.firstOrNull { it.index == HanSuggestionCandidate.PREVIOUS_CHIP }
        val moreBeyond = han.any { it.index == HanSuggestionCandidate.NEXT_CHIP }
        val next = HanSuggestionCandidate("▸", HanSuggestionCandidate.NEXT_CHIP)

        @Composable
        fun Cell(c: HanSuggestionCandidate) = Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable {
                    FlorisImeService.inputFeedbackController()?.keyPress()
                    keyboardManager.commitCandidate(c)
                }
                .padding(horizontal = CELL_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            SnyggText(elementName = "${FlorisImeUi.SmartbarCandidateWord.elementName}-text", text = c.text.toString())
        }

        Layout(
            modifier = modifier.fillMaxSize(),
            content = {
                words.forEach { Cell(it) }
                Cell(previous ?: HanSuggestionCandidate("◂", HanSuggestionCandidate.PREVIOUS_CHIP))
                Cell(next)
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            // Two lines when the Smartbar made room for them, else one (a theme's odd sizing, say).
            val lines = if (height >= lineHeight.roundToPx() * 3 / 2) LINES else 1
            val lineH = height / lines
            val minCell = MIN_CELL.roundToPx()
            val wordMeasurables = measurables.subList(0, words.size)
            val prevMeasurable = measurables[words.size]
            val nextMeasurable = measurables[words.size + 1]
            fun natural(m: Measurable) = maxOf(minCell, m.maxIntrinsicWidth(lineH))
            val naturals = wordMeasurables.map(::natural)
            val prevWidth = if (previous != null) natural(prevMeasurable) else 0
            val nextWidth = natural(nextMeasurable)

            // Fill the lines in order; the last line keeps room for the arrows.
            fun fill(lastLineArrows: Int): List<List<Int>> {
                val out = ArrayList<List<Int>>()
                var i = 0
                for (line in 0 until lines) {
                    val available = width - if (line == lines - 1) lastLineArrows else 0
                    val row = ArrayList<Int>()
                    var used = 0
                    while (i < naturals.size && used + naturals[i] <= available) {
                        used += naturals[i]
                        row += i
                        i++
                    }
                    // A word wider than a whole line still gets a cell of its own.
                    if (row.isEmpty() && i < naturals.size && line == 0) {
                        row += i
                        i++
                    }
                    out += row
                }
                return out
            }
            var rows = fill(prevWidth + if (moreBeyond) nextWidth else 0)
            val fitsAll = rows.sumOf { it.size } == naturals.size
            val showNext = moreBeyond || !fitsAll
            if (showNext && !moreBeyond) rows = fill(prevWidth + nextWidth)
            // Even the lines out (8 + 2 reads as a full line and a stray pair): move words down while the
            // last line has room for them.
            if (rows.size == LINES) {
                val first = rows[0].toMutableList()
                val last = rows[1].toMutableList()
                val lastAvailable = width - prevWidth - if (showNext) nextWidth else 0
                while (first.size - last.size > 1 &&
                    last.sumOf { naturals[it] } + naturals[first.last()] <= lastAvailable
                ) {
                    last.add(0, first.removeAt(first.size - 1))
                }
                rows = listOf(first, last)
            }
            ZizaiIme.current?.stripShown = rows.sumOf { it.size }

            fun fixed(w: Int) = Constraints.fixed(w.coerceIn(0, width), lineH)
            val placed = rows.mapIndexed { line, row ->
                val arrows = if (line == lines - 1) prevWidth + (if (showNext) nextWidth else 0) else 0
                val extra = if (row.isEmpty()) 0 else ((width - arrows - row.sumOf { naturals[it] }) / row.size).coerceAtLeast(0)
                row.map { wordMeasurables[it].measure(fixed(naturals[it] + extra)) }
            }
            val prevPlaceable = if (previous != null) prevMeasurable.measure(fixed(prevWidth)) else null
            val nextPlaceable = if (showNext) nextMeasurable.measure(fixed(nextWidth)) else null
            layout(width, height) {
                placed.forEachIndexed { line, row ->
                    var x = 0
                    row.forEach { it.place(x, line * lineH); x += it.width }
                }
                val lastY = (lines - 1) * lineH
                nextPlaceable?.let { it.place(width - it.width, lastY) }
                prevPlaceable?.let { it.place(width - it.width - (nextPlaceable?.width ?: 0), lastY) }
            }
        }
    }
}
