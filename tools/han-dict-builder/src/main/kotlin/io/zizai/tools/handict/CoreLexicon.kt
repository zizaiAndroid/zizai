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
import io.zizai.han.reading.Mandarin
import java.util.TreeMap
import kotlin.math.ln
import kotlin.math.roundToInt

class CharRecord(val codePoint: Int, val readings: List<ReadingWeight>, val tier: Int, val strokes: Int, val flags: Int)

/** A packed reading (`syllable shl 3 or tone`, tone 0 when unknown) and its weight for this character. */
data class ReadingWeight(val packed: Int, val weight: Int)

class Posting(val codePoint: Int, val toneMask: Int, val tier: Int, val flags: Int, val score: Int)

class WordRecord(val syllables: IntArray, val score: Int, val text: String)

/** The merged core lexicon, ready to be written by [LexiconWriter]. */
class CoreLexicon(val chars: List<CharRecord>, val postings: List<List<Posting>>, val words: List<WordRecord>) {
    companion object {
        /** Quantized log frequency, 0–255. */
        fun score(frequency: Double): Int = (ln(1.0 + frequency) * 16).roundToInt().coerceIn(0, 255)

        fun build(unihan: TreeMap<Int, UnihanChar>, aosp: List<AospEntry>): CoreLexicon {
            // AOSP single characters give per-(character, syllable) frequencies, so polyphones rank right.
            val charFreq = HashMap<Long, Double>()
            fun key(cp: Int, syllable: Int) = (cp.toLong() shl 16) or syllable.toLong()
            val allChars = java.util.TreeSet(unihan.keys)
            for (e in aosp) {
                if (e.syllables.size != 1) continue
                val cp = e.text.codePointAt(0)
                allChars += cp
                val k = key(cp, e.syllables[0])
                charFreq[k] = maxOf(charFreq[k] ?: 0.0, e.frequency)
            }

            val chars = ArrayList<CharRecord>(allChars.size)
            val postings = List(Mandarin.syllables.size) { ArrayList<Posting>() }
            val postingOrder = List(Mandarin.syllables.size) { ArrayList<Pair<PostingKey, Posting>>() }
            for (cp in allChars) {
                val u = unihan[cp]
                val tier = u?.tier ?: 0
                val flags = flagsOf(u)
                // Toned readings from Unihan, then AOSP syllables Unihan doesn't know, with tone 0.
                val weights = ArrayList<ReadingWeight>()
                val toneMasks = TreeMap<Int, Int>()
                val inMandarin = HashSet<Int>()
                u?.readings?.forEach { (r, pinlu) ->
                    val aospScore = charFreq[key(cp, r.syllable)]?.let(::score) ?: 0
                    val pinluScore = (ln(1.0 + pinlu) * 16).roundToInt().coerceAtMost(127)
                    val mandarin = r in u.mandarin
                    if (mandarin) inMandarin += r.syllable
                    weights += ReadingWeight(
                        LexiconFormat.packReading(r.syllable, r.tone),
                        (aospScore shl 8) or (if (mandarin) 128 else 0) or pinluScore,
                    )
                    toneMasks.merge(r.syllable, 1 shl r.tone, Int::or)
                }
                for (syllable in 0 until Mandarin.syllables.size) {
                    val f = charFreq[key(cp, syllable)] ?: continue
                    if (syllable in toneMasks) continue
                    weights += ReadingWeight(LexiconFormat.packReading(syllable, 0), score(f) shl 8)
                    toneMasks[syllable] = 0
                }
                if (weights.isEmpty()) continue
                weights.sortByDescending { it.weight } // stable: source order breaks ties
                chars += CharRecord(cp, weights, tier, u?.strokes ?: 0, flags)

                for ((syllable, mask) in toneMasks) {
                    val s = charFreq[key(cp, syllable)]?.let(::score) ?: 0
                    val pinlu = u?.readings?.entries?.filter { it.key.syllable == syllable }?.maxOfOrNull { it.value } ?: 0
                    val k = PostingKey(s, syllable in inMandarin, tierRank(tier), pinlu, u?.strokes ?: 99, cp)
                    postingOrder[syllable] += k to Posting(cp, mask, tier, flags, s)
                }
            }
            for (s in postings.indices) {
                postingOrder[s].sortWith(compareBy { it.first })
                postingOrder[s].mapTo(postings[s]) { it.second }
            }

            val wordBest = TreeMap<String, AospEntry>()
            for (e in aosp) {
                if (e.syllables.size < 2) continue
                val k = e.text + "\u0000" + e.syllables.joinToString(",")
                val prev = wordBest[k]
                if (prev == null || e.frequency > prev.frequency) wordBest[k] = e
            }
            val words = wordBest.values.map { WordRecord(it.syllables, score(it.frequency), it.text) }
                .sortedWith(WORD_ORDER)
            return CoreLexicon(chars, postings, words)
        }

        val WORD_ORDER: Comparator<WordRecord> = Comparator { a, b ->
            val c = compareSyllables(a.syllables, b.syllables)
            when {
                c != 0 -> c
                a.score != b.score -> b.score - a.score
                else -> a.text.compareTo(b.text)
            }
        }

        fun compareSyllables(a: IntArray, b: IntArray): Int {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i] - b[i]
            return a.size - b.size
        }

        private fun tierRank(tier: Int) = if (tier == 0) 9 else tier

        private fun flagsOf(u: UnihanChar?): Int {
            u ?: return 0
            var f = 0
            if (u.gb2312) f = f or LexiconFormat.FLAG_GB2312
            if (u.big5 >= 0) f = f or LexiconFormat.FLAG_BIG5
            if (u.big5 in 0xA440..0xC67E) f = f or LexiconFormat.FLAG_BIG5_LEVEL1
            if (u.traditionalVariants.any { it != u.codePoint }) f = f or LexiconFormat.FLAG_SIMPLIFIED
            if (u.simplifiedVariants.any { it != u.codePoint }) f = f or LexiconFormat.FLAG_TRADITIONAL
            return f
        }
    }

    /** Homophone order (docs/ARCHITECTURE.md §6.2): frequency, standard reading, tier, kHanyuPinlu, strokes. */
    private data class PostingKey(
        val score: Int,
        val standard: Boolean,
        val tierRank: Int,
        val pinlu: Int,
        val strokes: Int,
        val codePoint: Int,
    ) : Comparable<PostingKey> {
        override fun compareTo(other: PostingKey): Int = compareValuesBy(
            this, other,
            { -it.score }, { if (it.standard) 0 else 1 }, { it.tierRank }, { -it.pinlu }, { it.strokes }, { it.codePoint },
        )
    }
}
