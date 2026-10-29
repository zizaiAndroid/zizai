/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.reading

import java.text.Normalizer

/** Hanyu Pinyin spelling rules. Internally ü is spelled `v`, as typed on a keyboard. */
object Pinyin {
    private const val VOWELS = "aeiouv"
    private val MARKS = charArrayOf('̄', '́', '̌', '̀') // tones 1–4

    /** Maps ü and u: to v, and the common `lue`/`nue` to lüe/nüe. */
    fun normalizeSpelling(spelling: String): String {
        val s = spelling.replace("ü", "v").replace("u:", "v")
        return when (s) {
            "lue" -> "lve"
            "nue" -> "nve"
            else -> s
        }
    }

    /** Display form of a toneless spelling: `lv` → `lü`. */
    fun display(spelling: String): String = spelling.replace('v', 'ü')

    /**
     * Tone-marked display form: (`shi`, 4) → `shì`, (`lv`, 3) → `lǚ`, (`ng`, 2) → `ńg`.
     * Tone 0 and the neutral tone 5 are unmarked.
     *
     * The mark goes on a or e if present; on the o of ou; otherwise on the last vowel; and on the
     * syllabic consonant of syllables with no vowel (m, n, ng, hm, hng).
     */
    fun toneMarked(spelling: String, tone: Int): String {
        if (tone !in 1..4) return display(spelling)
        val sb = StringBuilder(display(spelling))
        sb.insert(markPosition(spelling) + 1, MARKS[tone - 1])
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    fun toneMarked(reading: Reading): String = toneMarked(reading.spelling(), reading.tone)

    /**
     * Parses a tone-marked spelling as dictionaries write it (`shì`, `lǚ`, `ňg`, `ḿ`). No mark means the
     * neutral tone. Returns null for anything outside the inventory (such as `ê`).
     */
    fun parseToneMarked(text: String): Reading? {
        val nfd = Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
        var tone = Mandarin.NEUTRAL_TONE
        val sb = StringBuilder(nfd.length)
        for (c in nfd) {
            val mark = MARKS.indexOf(c)
            when {
                mark >= 0 -> tone = mark + 1
                c == '̈' -> if (sb.isNotEmpty() && sb.last() == 'u') sb.setCharAt(sb.length - 1, 'v') else return null
                else -> sb.append(c)
            }
        }
        val id = Mandarin.syllables.idOf(normalizeSpelling(sb.toString()))
        return if (id < 0) null else Reading.of(Mandarin, id, tone)
    }

    private fun markPosition(s: String): Int {
        s.indexOf('a').let { if (it >= 0) return it }
        s.indexOf('e').let { if (it >= 0) return it }
        s.indexOf("ou").let { if (it >= 0) return it }
        val last = s.indexOfLast { it in VOWELS }
        if (last >= 0) return last
        return if (s.startsWith("h")) 1 else 0
    }
}
