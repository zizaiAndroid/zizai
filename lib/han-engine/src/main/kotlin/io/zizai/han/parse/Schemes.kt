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
import io.zizai.han.reading.SyllableTable

/** Toggleable fuzzy Pinyin pairs (模糊音, docs/ARCHITECTURE.md §6.2), for speakers who merge these sounds. */
enum class Fuzzy { Z_ZH, C_CH, S_SH, N_L, R_L, F_H, AN_ANG, EN_ENG, IN_ING, IAN_IANG, UAN_UANG }

/** How the letters spell syllables: full Pinyin, or a double-Pinyin (双拼) scheme of two keys per syllable. */
enum class PinyinScheme { QUANPIN, XIAOHE, ZIRANMA, MICROSOFT }

/** The spellings a fuzzy speaker might mean by [spelling], not including itself. */
internal fun fuzzyVariants(spelling: String, fuzzy: Set<Fuzzy>): List<String> {
    if (fuzzy.isEmpty()) return emptyList()
    val initials = ArrayList<Pair<String, String>>() // (initial as typed, alternative)
    fun swap(a: String, b: String, f: Fuzzy) {
        if (f !in fuzzy) return
        when {
            spelling.startsWith(b) -> initials += b to a
            spelling.startsWith(a) -> initials += a to b
        }
    }
    swap("z", "zh", Fuzzy.Z_ZH)
    swap("c", "ch", Fuzzy.C_CH)
    swap("s", "sh", Fuzzy.S_SH)
    swap("n", "l", Fuzzy.N_L)
    swap("r", "l", Fuzzy.R_L)
    swap("f", "h", Fuzzy.F_H)
    val heads = listOf(spelling) + initials.map { (typed, alt) -> alt + spelling.removePrefix(typed) }

    fun finals(s: String): List<String> {
        val out = arrayListOf(s)
        fun pair(short: String, long: String, f: Fuzzy) {
            if (f !in fuzzy) return
            when {
                s.endsWith(long) -> out += s.dropLast(long.length) + short
                s.endsWith(short) -> out += s.dropLast(short.length) + long
            }
        }
        // The longer finals first, so ian/uan aren't also read as an.
        when {
            s.endsWith("iang") || s.endsWith("ian") -> pair("ian", "iang", Fuzzy.IAN_IANG)
            s.endsWith("uang") || s.endsWith("uan") -> pair("uan", "uang", Fuzzy.UAN_UANG)
            s.endsWith("ang") || s.endsWith("an") -> pair("an", "ang", Fuzzy.AN_ANG)
            s.endsWith("eng") || s.endsWith("en") -> pair("en", "eng", Fuzzy.EN_ENG)
            s.endsWith("ing") || s.endsWith("in") -> pair("in", "ing", Fuzzy.IN_ING)
        }
        return out
    }
    return heads.flatMap(::finals).distinct().filter { it != spelling }
}

/**
 * Double Pinyin (双拼): each syllable is two keys, an initial key and a final key, so the lattice has one
 * reading per key pair; an odd last key is an initial still waiting for its final.
 */
object DoublePinyin {
    private class Table(val initials: Map<Char, String>, val finals: Map<Char, List<String>>, val zeroInitialKey: Char?)

    private val INITIALS: Map<Char, String> = "bpmfdtnlgkhjqxrzcsyw".associateWith { it.toString() } +
        mapOf('v' to "zh", 'i' to "ch", 'u' to "sh")

    private fun finals(vararg pairs: Pair<Char, String>) = pairs.associate { (k, v) -> k to v.split('/') }

    private val XIAOHE = Table(
        INITIALS,
        finals(
            'q' to "iu", 'w' to "ei", 'r' to "uan/van", 't' to "ue/ve", 'y' to "un/vn", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "ie", 'a' to "a", 's' to "ong/iong", 'd' to "ai", 'f' to "en", 'g' to "eng",
            'h' to "ang", 'j' to "an", 'k' to "ing/uai", 'l' to "iang/uang", 'z' to "ou", 'x' to "ia/ua",
            'c' to "ao", 'v' to "ui/v", 'b' to "in", 'n' to "iao", 'm' to "ian", 'e' to "e",
        ),
        zeroInitialKey = null,
    )

    private val ZIRANMA = Table(
        INITIALS,
        finals(
            'q' to "iu", 'w' to "ia/ua", 'r' to "uan/van", 't' to "ue/ve", 'y' to "ing/uai", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "un/vn", 'a' to "a", 's' to "ong/iong", 'd' to "iang/uang", 'f' to "en",
            'g' to "eng", 'h' to "ang", 'j' to "an", 'k' to "ao", 'l' to "ai", 'z' to "ei", 'x' to "ie",
            'c' to "iao", 'v' to "ui/v", 'b' to "ou", 'n' to "in", 'm' to "ian", 'e' to "e",
        ),
        zeroInitialKey = null,
    )

    private val MICROSOFT = Table(
        INITIALS,
        finals(
            'q' to "iu", 'w' to "ia/ua", 'r' to "uan/van/er", 't' to "ue", 'y' to "uai/v", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "un/vn", 'a' to "a", 's' to "ong/iong", 'd' to "iang/uang", 'f' to "en",
            'g' to "eng", 'h' to "ang", 'j' to "an", 'k' to "ao", 'l' to "ai", ';' to "ing", 'z' to "ei",
            'x' to "ie", 'c' to "iao", 'v' to "ui/ve", 'b' to "ou", 'n' to "in", 'm' to "ian", 'e' to "e",
        ),
        zeroInitialKey = 'o',
    )

    private fun table(scheme: PinyinScheme) = when (scheme) {
        PinyinScheme.XIAOHE -> XIAOHE
        PinyinScheme.ZIRANMA -> ZIRANMA
        PinyinScheme.MICROSOFT -> MICROSOFT
        PinyinScheme.QUANPIN -> error("full Pinyin has no double-Pinyin table")
    }

    /** Keys the scheme uses besides letters (Microsoft types ing on `;`). */
    fun extraKeys(scheme: PinyinScheme): Set<Char> = if (scheme == PinyinScheme.MICROSOFT) setOf(';') else emptySet()

    /** The syllable ids two keys may spell under [scheme]. */
    fun syllables(k1: Char, k2: Char, scheme: PinyinScheme, table: SyllableTable = Mandarin.syllables): List<Int> {
        val t = table(scheme)
        val spellings = LinkedHashSet<String>()
        val zeroInitial = if (t.zeroInitialKey != null) k1 == t.zeroInitialKey else k1 in "aeo"
        if (zeroInitial) {
            // Syllables without an initial: o + final key (Microsoft), or the final's own first letter.
            for (f in t.finals[k2].orEmpty()) {
                if (t.zeroInitialKey != null || f.startsWith(k1)) spellings += f
            }
            if (t.zeroInitialKey == null) {
                if (k1 == k2) spellings += k1.toString() // aa, ee, oo
                spellings += "$k1$k2" // ai, an, ao, ei, en, er, ou
            }
        } else {
            val initial = t.initials[k1] ?: return emptyList()
            for (f in t.finals[k2].orEmpty()) {
                // ü is written u after j, q, x and y.
                val fin = if (f.startsWith("v") && initial in setOf("j", "q", "x", "y")) "u" + f.drop(1) else f
                spellings += initial + fin
            }
        }
        return spellings.map(table::idOf).filter { it >= 0 }.distinct()
    }

    fun parse(input: String, scheme: PinyinScheme, table: SyllableTable = Mandarin.syllables): SyllableLattice {
        val n = input.length
        val outgoing = Array<List<SyllableEdge>>(n + 1) { emptyList() }
        var i = 0
        while (i < n) {
            if (input[i] == '\'') { i++; continue }
            val edges = ArrayList<SyllableEdge>(2)
            if (i + 1 < n && input[i + 1] != '\'') {
                for (s in syllables(input[i], input[i + 1], scheme, table)) {
                    edges += SyllableEdge(i, i + 2, EdgeKind.FULL, s, s)
                }
            } else {
                // A lone last key: an initial (or a bare vowel) waiting for its final.
                val initial = table(scheme).initials[input[i]] ?: input[i].toString()
                val range = table.idsWithPrefix(initial)
                if (!range.isEmpty()) edges += SyllableEdge(i, i + 1, EdgeKind.INITIAL, range.first, range.last)
            }
            if (edges.isEmpty()) {
                // Not a syllable in this scheme: pass both keys through.
                edges += SyllableEdge(i, i + 1, EdgeKind.RAW, -1, -1)
                outgoing[i] = edges
                if (i + 1 < n) outgoing[i + 1] = listOf(SyllableEdge(i + 1, i + 2, EdgeKind.RAW, -1, -1))
                i += 2
                continue
            }
            outgoing[i] = edges
            i += 2
        }
        return SyllableLattice(input, outgoing)
    }
}
