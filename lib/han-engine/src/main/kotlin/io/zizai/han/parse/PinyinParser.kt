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

import io.zizai.han.reading.Cantonese
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

    /** Parses [input] as full Pinyin; with [fuzzy], each syllable may also be read as its fuzzy partners. */
    fun parse(input: String, table: SyllableTable = Mandarin.syllables, fuzzy: Set<Fuzzy> = emptySet()): SyllableLattice =
        LatticeBuilder(table, INITIALS, RARE, Pinyin::normalizeSpelling) { fuzzyVariants(it, fuzzy) }.parse(input)
}

/**
 * Parses Jyutping (粵拼) the way [PinyinParser] parses Pinyin (P3-01): full syllables, initials standing for
 * a whole syllable (`ngm` → 我哋), apostrophes and an incomplete last syllable. Tones aren't typed; the tone
 * filter picks them. With [lazy], the common "lazy sounds" (懶音) also find the standard ones: l for n
 * (lei → nei 你), no initial for ng (o → ngo 我), g/k for gw/kw before o (gok → gwok 國), -n for -ng and -t
 * for -k.
 */
object JyutpingParser {
    private val INITIALS = setOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "ng", "h", "gw", "kw", "w", "z", "c", "s", "j",
    )
    private val RARE = setOf("m", "ng", "hm", "hng")

    fun parse(input: String, lazy: Boolean = false): SyllableLattice =
        LatticeBuilder(Cantonese.syllables, INITIALS, RARE, { it }) { if (lazy) lazyVariants(it) else emptyList() }.parse(input)

    /** The standard spellings a lazy-sound speaker might mean by [typed], not including itself. */
    fun lazyVariants(typed: String): List<String> {
        val heads = arrayListOf(typed)
        when {
            typed.startsWith("l") -> heads += "n" + typed.drop(1)
            typed.firstOrNull() in setOf('a', 'e', 'o', 'u') -> heads += "ng$typed"
            typed.startsWith("go") -> heads += "gw" + typed.drop(1)
            typed.startsWith("ko") -> heads += "kw" + typed.drop(1)
        }
        val out = LinkedHashSet<String>()
        for (h in heads) {
            out += h
            when {
                h.endsWith("n") && !h.endsWith("ng") -> out += h + "g"
                h.endsWith("t") -> out += h.dropLast(1) + "k"
            }
        }
        return out.filter { it != typed }
    }
}

/** The lattice construction both parsers share. */
internal class LatticeBuilder(
    private val table: SyllableTable,
    private val initials: Set<String>,
    private val rare: Set<String>,
    private val normalize: (String) -> String,
    private val variants: (String) -> List<String>,
) {
    fun parse(input: String): SyllableLattice {
        val n = input.length
        val outgoing = Array<List<SyllableEdge>>(n + 1) { emptyList() }
        for (start in 0 until n) {
            if (input[start] == '\'') continue
            val edges = ArrayList<SyllableEdge>(4)
            var end = start + 1
            while (end <= n && end - start <= table.maxLength + 1 && input[end - 1] != '\'') {
                addEdges(input, start, end, edges)
                end++
            }
            if (edges.none { it.end == start + 1 }) {
                edges.add(SyllableEdge(start, start + 1, EdgeKind.RAW, -1, -1))
            }
            outgoing[start] = edges
        }
        return SyllableLattice(input, outgoing)
    }

    private fun addEdges(input: String, start: Int, end: Int, out: MutableList<SyllableEdge>) {
        val sub = input.substring(start, end)
        val atEnd = end == input.length
        val spelling = normalize(sub)
        val id = table.idOf(spelling)
        if (id >= 0) {
            out.add(SyllableEdge(start, end, if (sub in rare) EdgeKind.RARE else EdgeKind.FULL, id, id))
        }
        for (v in variants(spelling)) {
            val vid = table.idOf(v)
            if (vid >= 0 && vid != id) out.add(SyllableEdge(start, end, EdgeKind.FULL, vid, vid, cost = FUZZY_COST))
        }
        val isInitial = sub in initials
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

    private companion object {
        /** A fuzzy or lazy reading costs a little more than the syllable as typed. */
        const val FUZZY_COST = 1.4
    }
}
