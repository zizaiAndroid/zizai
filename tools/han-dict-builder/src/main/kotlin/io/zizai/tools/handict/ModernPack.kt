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

import io.zizai.han.parse.EdgeKind
import io.zizai.han.parse.PinyinParser
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipFile
import kotlin.math.exp
import kotlin.math.ln

/**
 * Reads upstream's Pinyin pack (CC-CEDICT readings with OpenSubtitles counts, CC BY-SA 4.0; see
 * tools/pinyin-dict/ATTRIBUTION.md) for `han-ext-modern.bin` (docs/IMPLEMENTATION_PLAN.md P1-08).
 *
 * The pack is a `.flex` zip holding one SQLite table of `code / text / weight` rows, where `code` is the
 * toneless pinyin run together (`zhongguo`) and `weight` is log10 of a corpus count (slightly negative
 * for words the corpus never saw). Rows become [AospEntry]s on our frequency scale, so the core builder
 * merges them with the AOSP data unchanged.
 */
object ModernPack {
    class Row(val code: String, val text: String, val weight: Double)

    var unsegmented = 0
        private set

    fun read(flex: File): List<Row> {
        val tmp = Files.createTempFile("pinyin", ".sqlite3").toFile()
        try {
            ZipFile(flex).use { z ->
                val entry = z.getEntry("pinyin.sqlite3") ?: error("${flex.name}: no pinyin.sqlite3")
                z.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            DriverManager.getConnection("jdbc:sqlite:${tmp.absolutePath}").use { db ->
                db.createStatement().use { st ->
                    // A fixed order keeps the output deterministic.
                    val rs = st.executeQuery("SELECT code, text, weight FROM pinyin ORDER BY code, text, weight")
                    val out = ArrayList<Row>(180_000)
                    while (rs.next()) out += Row(rs.getString(1), rs.getString(2), rs.getDouble(3))
                    return out
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Splits a run-together code into one full syllable per character, or null if it can't. */
    fun segment(code: String, length: Int): IntArray? {
        val lattice = PinyinParser.parse(code)
        val path = lattice.bestPaths(16).firstOrNull { p ->
            p.edges.size == length && p.edges.all { it.kind == EdgeKind.FULL || it.kind == EdgeKind.RARE }
        } ?: return null
        return IntArray(length) { path.edges[it].first }
    }

    /**
     * Converts rows to entries on the AOSP frequency scale. Pack weights are log10 counts from a different
     * corpus, so they're shifted by the median difference on words both sources have; the slope is the
     * change of log base that our score uses (`16 · ln(1 + f)`).
     */
    fun toEntries(rows: List<Row>, aosp: List<AospEntry>): List<AospEntry> {
        val segmented = rows.mapNotNull { r ->
            val n = r.text.codePointCount(0, r.text.length)
            val syllables = segment(r.code, n)
            if (syllables == null) {
                unsegmented++
                null
            } else Triple(r, syllables, n)
        }
        val aospScore = HashMap<String, Int>()
        for (e in aosp) aospScore[key(e.text, e.syllables)] = maxOf(aospScore[key(e.text, e.syllables)] ?: 0, CoreLexicon.score(e.frequency))
        val diffs = segmented.mapNotNull { (r, syl, _) ->
            if (r.weight <= 0) null else aospScore[key(r.text, syl)]?.let { it - r.weight * SLOPE }
        }.sorted()
        val offset = if (diffs.isEmpty()) 0.0 else diffs[diffs.size / 2]
        return segmented.map { (r, syl, n) ->
            var score = if (r.weight <= 0) 0.0 else (r.weight * SLOPE + offset).coerceIn(0.0, 255.0)
            // A word common in the 2018 subtitles but missing from AOSP's 2009 list is most likely newer
            // usage (微信, 朋友圈), so it gets a little ahead of the old list's words of equal count.
            if (n > 1 && r.weight >= NEW_WORD_MIN_WEIGHT && key(r.text, syl) !in aospScore) {
                score = (score + NEW_WORD_BONUS).coerceAtMost(255.0)
            }
            AospEntry(r.text, exp(score / 16.0) - 1.0, syl)
        }
    }

    /** At least ~30 occurrences in the corpus, so rare compositions don't get the bonus. */
    private const val NEW_WORD_MIN_WEIGHT = 1.5

    /** Score units, about a factor of 1.9 in frequency. */
    private const val NEW_WORD_BONUS = 10.0

    /** Score units per log10 unit: 16 · ln(10). */
    private val SLOPE = 16 * ln(10.0)

    private fun key(text: String, syllables: IntArray) = text + "\u0000" + syllables.joinToString(",")
}
