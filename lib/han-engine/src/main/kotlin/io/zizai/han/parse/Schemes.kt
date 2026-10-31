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
enum class PinyinScheme { QUANPIN, XIAOHE, ZIRANMA, MICROSOFT, SOGOU, ZIGUANG, JIAJIA }

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
    /**
     * A scheme's keys. A syllable without an initial is typed as [zeroInitialKey] plus its final key, or
     * with [naturalZero] as its own first letter plus its final key (`ah` ang, `aa` a, `ai` ai); some
     * schemes take both.
     */
    private class Table(
        val initials: Map<Char, String>,
        val finals: Map<Char, List<String>>,
        val zeroInitialKey: Char?,
        val naturalZero: Boolean = zeroInitialKey == null,
    )

    private val INITIALS: Map<Char, String> = "bpmfdtnlgkhjqxrzcsyw".associateWith { it.toString() } +
        mapOf('v' to "zh", 'i' to "ch", 'u' to "sh")

    /** Jiajia (拼音加加) swaps sh and ch; Ziguang (紫光) puts ch on `a`. */
    private val INITIALS_JIAJIA = INITIALS + mapOf('i' to "sh", 'u' to "ch")
    private val INITIALS_ZIGUANG = "bpmfdtnlgkhjqxrzcsyw".associateWith { it.toString() } +
        mapOf('u' to "zh", 'a' to "ch", 'i' to "sh")

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

    /** Microsoft's layout with ve on `t` too; zero initials either way. */
    private val SOGOU = Table(
        INITIALS,
        finals(
            'q' to "iu", 'w' to "ia/ua", 'r' to "uan/van/er", 't' to "ue/ve", 'y' to "uai/v", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "un/vn", 'a' to "a", 's' to "ong/iong", 'd' to "iang/uang", 'f' to "en",
            'g' to "eng", 'h' to "ang", 'j' to "an", 'k' to "ao", 'l' to "ai", ';' to "ing", 'z' to "ei",
            'x' to "ie", 'c' to "iao", 'v' to "ui/ve", 'b' to "ou", 'n' to "in", 'm' to "ian", 'e' to "e",
        ),
        zeroInitialKey = 'o',
        naturalZero = true,
    )

    private val ZIGUANG = Table(
        INITIALS_ZIGUANG,
        finals(
            'q' to "ao", 'w' to "en", 'r' to "an", 't' to "eng", 'y' to "in/uai", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "ai", 'a' to "a", 's' to "ang", 'd' to "ie", 'f' to "ian", 'g' to "iang/uang",
            'h' to "ong/iong", 'j' to "iu/er", 'k' to "ei", 'l' to "uan/van", ';' to "ing", 'z' to "ou",
            'x' to "ia/ua", 'b' to "iao", 'n' to "ui/ue/ve", 'm' to "un/vn", 'v' to "v", 'e' to "e",
        ),
        zeroInitialKey = 'o',
    )

    private val JIAJIA = Table(
        INITIALS_JIAJIA,
        finals(
            'q' to "er/ing", 'w' to "ei", 'r' to "en", 't' to "eng", 'y' to "ong/iong", 'u' to "u", 'i' to "i",
            'o' to "uo/o", 'p' to "ou", 'a' to "a", 's' to "ai", 'd' to "ao", 'f' to "an", 'g' to "ang",
            'h' to "iang/uang", 'j' to "ian", 'k' to "iao", 'l' to "in", 'z' to "un/vn", 'x' to "ue/ve/uai",
            'c' to "uan/van", 'v' to "ui/v", 'b' to "ia/ua", 'n' to "iu", 'm' to "ie", 'e' to "e",
        ),
        zeroInitialKey = 'o',
        naturalZero = true,
    )

    private fun table(scheme: PinyinScheme) = when (scheme) {
        PinyinScheme.XIAOHE -> XIAOHE
        PinyinScheme.ZIRANMA -> ZIRANMA
        PinyinScheme.MICROSOFT -> MICROSOFT
        PinyinScheme.SOGOU -> SOGOU
        PinyinScheme.ZIGUANG -> ZIGUANG
        PinyinScheme.JIAJIA -> JIAJIA
        PinyinScheme.QUANPIN -> error("full Pinyin has no double-Pinyin table")
    }

    /** Keys the scheme uses besides letters (Microsoft, Sogou and Ziguang type ing on `;`). */
    fun extraKeys(scheme: PinyinScheme): Set<Char> =
        if (scheme != PinyinScheme.QUANPIN && table(scheme).finals.containsKey(';')) setOf(';') else emptySet()

    /** The syllable ids two keys may spell under [scheme]. */
    fun syllables(k1: Char, k2: Char, scheme: PinyinScheme, table: SyllableTable = Mandarin.syllables): List<Int> {
        val t = table(scheme)
        val spellings = LinkedHashSet<String>()
        val fixed = k1 == t.zeroInitialKey
        val natural = t.naturalZero && k1 in "aeo"
        if (fixed || natural) {
            // Syllables without an initial: o + final key (Microsoft), or the final's own first letter.
            for (f in t.finals[k2].orEmpty()) {
                if (fixed || f.startsWith(k1)) spellings += f
            }
            if (natural) {
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
