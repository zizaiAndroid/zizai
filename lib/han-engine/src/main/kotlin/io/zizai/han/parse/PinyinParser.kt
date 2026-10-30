/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.parse

import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Pinyin
import io.zizai.han.reading.SyllableTable

/**
 * Parses a quanpin, jianpin or mixed buffer (`xian`, `xi'an`, `zg`, `zhongg`, `lv`, `nve`) into a
 * [SyllableLattice]. The buffer holds lowercase letters and apostrophes; anything else is the caller's
 * business.
 */
object PinyinParser {
    /** Initials that may stand for a whole syllable in jianpin. `z` also covers `zh` (中国 from `zg`). */
    private val INITIALS = setOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
        "zh", "ch", "sh", "r", "z", "c", "s", "y", "w",
    )
    private val RARE = setOf("m", "n", "ng", "hm", "hng")

    fun parse(input: String, table: SyllableTable = Mandarin.syllables): SyllableLattice {
        val n = input.length
        val outgoing = Array<List<SyllableEdge>>(n + 1) { emptyList() }
        for (start in 0 until n) {
            if (input[start] == '\'') continue
            val edges = ArrayList<SyllableEdge>(4)
            var end = start + 1
            while (end <= n && end - start <= table.maxLength + 1 && input[end - 1] != '\'') {
                addEdges(input, start, end, table, edges)
                end++
            }
            if (edges.none { it.end == start + 1 }) {
                edges.add(SyllableEdge(start, start + 1, EdgeKind.RAW, -1, -1))
            }
            outgoing[start] = edges
        }
        return SyllableLattice(input, outgoing)
    }

    private fun addEdges(input: String, start: Int, end: Int, table: SyllableTable, out: MutableList<SyllableEdge>) {
        val sub = input.substring(start, end)
        val atEnd = end == input.length
        val id = table.idOf(Pinyin.normalizeSpelling(sub))
        if (id >= 0) {
            out.add(SyllableEdge(start, end, if (sub in RARE) EdgeKind.RARE else EdgeKind.FULL, id, id))
        }
        val isInitial = sub in INITIALS
        if (!isInitial && !atEnd) return
        val range = table.idsWithPrefix(sub)
        if (range.isEmpty()) return
        when {
            isInitial -> out.add(SyllableEdge(start, end, EdgeKind.INITIAL, range.first, range.last))
            id < 0 -> out.add(SyllableEdge(start, end, EdgeKind.PREFIX, range.first, range.last))
            // A complete last syllable can also be the start of a longer one: `xia` → xian, xiang, xiao.
            // Costly enough that a very common completion doesn't beat the exact match (weixin: 卫星,
            // which is wei'xing, over 微信), yet a half-typed word still wins (beijin: 北京).
            range.first != range.last -> out.add(
                SyllableEdge(start, end, EdgeKind.PREFIX, range.first, range.last, cost = 3.0),
            )
        }
    }
}
