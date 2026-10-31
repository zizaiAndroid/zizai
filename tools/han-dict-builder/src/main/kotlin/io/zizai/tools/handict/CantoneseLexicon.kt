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
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * `han-yue.bin`: the Cantonese lexicon, for flipping Cantonese dictation (P2-10) and Jyutping typing
 * (P3-01). Characters and their readings come from rime-cantonese (with each reading's share) and Unihan
 * (kCantonese, kSMSZD2003Readings); words and frequencies from rime-cantonese.
 *
 * A character's frequency is its count in rime's essay. Characters the essay lacks fall back to how
 * common they are in Mandarin text, kept low, with a floor for written-Cantonese characters (in Hong
 * Kong's kUnihanCore2020 set but not the mainland's: 喺, 佢, 冇, 咗). A reading's share shifts the score
 * per syllable, so 行 ranks higher under hang4 than under hong4.
 */
object CantoneseLexicon {
    fun build(unihan: TreeMap<Int, UnihanChar>, core: CoreLexicon, rime: RimeCantonese? = null): CoreLexicon {
        val table = Cantonese.syllables
        val mandarin = core.chars.associate { c -> c.codePoint to c.readings.maxOf { it.weight ushr 8 } }
        val rimeReadings = rime?.chars.orEmpty().groupBy { it.codePoint }
        val counts = rime?.counts.orEmpty()

        val chars = ArrayList<CharRecord>()
        val postings = List(table.size) { ArrayList<Pair<List<Int>, Posting>>() }
        for (cp in (unihan.filterValues { it.cantonese.isNotEmpty() }.keys + rimeReadings.keys).toSortedSet()) {
            val u = unihan[cp]
            val hkCore = u?.core2020?.contains('H') == true
            val cantoneseOnly = hkCore && u?.core2020?.contains('G') == false
            val essay = counts[String(Character.toChars(cp))]
            val base = when {
                essay != null -> CoreLexicon.score(essay.toDouble())
                cantoneseOnly -> CANTONESE_FLOOR
                else -> minOf(mandarin[cp] ?: 0, MANDARIN_CAP)
            }
            // Readings: rime's, most common first, then any Unihan adds.
            val ordered = ArrayList<Triple<Int, Int, Int?>>() // syllable id, tone, share %
            rimeReadings[cp].orEmpty().sortedByDescending { it.share ?: 100 }.forEach { r ->
                table.idOf(r.syllable).takeIf { it >= 0 }?.let { ordered += Triple(it, r.tone, r.share) }
            }
            u?.cantonese?.forEach { (s, t) ->
                val id = table.idOf(s)
                if (id >= 0 && ordered.none { it.first == id && it.second == t }) ordered += Triple(id, t, null)
            }
            if (ordered.isEmpty()) continue
            val flags = CoreLexicon.flagsOf(u)
            val tier = u?.tier ?: 0
            val strokes = u?.strokes ?: 0
            chars += CharRecord(
                cp, ordered.mapIndexed { i, (s, t, _) -> ReadingWeight(LexiconFormat.packReading(s, t), 0xFFFF - i) },
                tier, strokes, flags,
            )
            val bySyllable = ordered.groupBy { it.first }
            bySyllable.entries.forEachIndexed { rank, (s, rs) ->
                val mask = rs.fold(0) { m, r -> m or (1 shl r.second) }
                val share = rs.mapNotNull { it.third }.sum().takeIf { rs.any { r -> r.third != null } }
                val score = if (share == null) base else (base + 16 * ln(maxOf(share, 1) / 100.0)).roundToInt().coerceIn(0, 255)
                val key = listOf(-score, rank, if (hkCore) 0 else 1, CoreLexicon.tierRank(tier), strokes, cp)
                postings[s] += key to Posting(cp, mask, tier, flags, score)
            }
        }
        val sorted = postings.map { list ->
            list.sortedWith { a, b -> a.first.zip(b.first).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0 }
                .map { it.second }
        }
        // A few syllables occur only inside rime's words (deu, gwek, …): no character lists, words still match.

        val best = TreeMap<String, WordRecord>()
        fun add(text: String, ids: List<Int>, count: Long) {
            if (ids.any { it < 0 }) return
            val record = WordRecord(ids.toIntArray(), CoreLexicon.score(count.toDouble()), text)
            val key = text + "\u0000" + ids.joinToString(",")
            if ((best[key]?.score ?: -1) < record.score) best[key] = record
        }
        for (w in rime?.words.orEmpty()) {
            // The essay is mostly written text and misses much colloquial Cantonese (我哋, 佢哋), so a
            // curated word it lacks gets a count estimated from its rarest character.
            val count = counts[w.text]
                ?: (w.text.codePoints().toArray().minOf { counts[String(Character.toChars(it))] ?: 0L } / UNSEEN_DIVISOR)
            add(w.text, w.syllables.map { table.idOf(it.first) }, count)
        }
        // Common essay words the word list lacks (你好), read with each character's main reading.
        val listed = rime?.words.orEmpty().mapTo(HashSet()) { it.text }
        val mainReading = rimeReadings.mapValues { (_, rs) -> rs.maxBy { it.share ?: 100 }.syllable }
        for ((text, count) in counts) {
            if (count < ESSAY_WORD_MIN || text in listed) continue
            val cps = text.codePoints().toArray()
            if (cps.size < 2) continue
            add(text, cps.map { cp -> mainReading[cp]?.let(table::idOf) ?: -1 }, count)
        }
        return CoreLexicon(chars, sorted, best.values.sortedWith(CoreLexicon.WORD_ORDER))
    }

    /** A curated word missing from the essay counts as its rarest character's count over this. */
    private const val UNSEEN_DIVISOR = 100L

    /** Essay words not in the word list are added from this count up. */
    private const val ESSAY_WORD_MIN = 1_000L

    /** About the frequency of a common Mandarin character (score scale 0–255). */
    private const val CANTONESE_FLOOR = 130

    /** Mandarin frequency stands in for missing Cantonese counts, but never ahead of real ones. */
    private const val MANDARIN_CAP = 60
}
