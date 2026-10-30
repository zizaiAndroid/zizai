/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.tools.handict

import io.zizai.han.lexicon.LexiconFormat
import io.zizai.han.reading.Cantonese
import java.util.TreeMap

/**
 * `han-yue.bin` (docs/IMPLEMENTATION_PLAN.md P2-10): every character's Cantonese readings from Unihan's
 * kCantonese, with homophone lists per Jyutping syllable, so the flip works on Cantonese dictation.
 *
 * No license-clean Cantonese frequencies are in the core sources (rime-cantonese's come with Jyutping
 * typing in Phase 3), so characters are ranked by how common they are in Mandarin text, which carries
 * over well for written characters. Characters in Hong Kong's core set but not the mainland's (喺, 佢,
 * 嘅, 冇, 咗: written Cantonese) get a floor, since Mandarin text hardly uses them; then Hong Kong core
 * characters, tier and strokes break ties. No words yet.
 */
object CantoneseLexicon {
    fun build(unihan: TreeMap<Int, UnihanChar>, core: CoreLexicon): CoreLexicon {
        val table = Cantonese.syllables
        val frequency = core.chars.associate { c -> c.codePoint to c.readings.maxOf { it.weight ushr 8 } }
        val chars = ArrayList<CharRecord>()
        val postings = List(table.size) { ArrayList<Pair<List<Int>, Posting>>() }
        for ((cp, u) in unihan) {
            if (u.cantonese.isEmpty()) continue
            val hkCore = 'H' in u.core2020
            val cantoneseOnly = hkCore && 'G' !in u.core2020
            val score = maxOf(frequency[cp] ?: 0, if (cantoneseOnly) CANTONESE_FLOOR else 0)
            val flags = CoreLexicon.flagsOf(u)
            val readings = ArrayList<ReadingWeight>()
            val masks = LinkedHashMap<Int, Int>()
            u.cantonese.forEachIndexed { i, (spelling, tone) ->
                val id = table.idOf(spelling)
                if (id < 0) return@forEachIndexed
                // Unihan lists the commonest reading first; keep that order.
                readings += ReadingWeight(LexiconFormat.packReading(id, tone), 0xFFFF - i)
                masks.merge(id, 1 shl tone, Int::or)
            }
            if (readings.isEmpty()) continue
            chars += CharRecord(cp, readings, u.tier, u.strokes, flags)
            masks.keys.forEachIndexed { rank, s ->
                val key = listOf(-score, rank, if (hkCore) 0 else 1, CoreLexicon.tierRank(u.tier), u.strokes, cp)
                postings[s] += key to Posting(cp, masks.getValue(s), u.tier, flags, score)
            }
        }
        val sorted = postings.map { list ->
            list.sortedWith { a, b -> a.first.zip(b.first).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0 }
                .map { it.second }
        }
        val empty = sorted.indices.filter { sorted[it].isEmpty() }.map(table::spellingOf)
        check(empty.isEmpty()) { "Jyutping syllables without characters: $empty" }
        return CoreLexicon(chars, sorted, emptyList())
    }

    /** About the frequency of a common Mandarin character (score scale 0–255). */
    private const val CANTONESE_FLOOR = 130
}
