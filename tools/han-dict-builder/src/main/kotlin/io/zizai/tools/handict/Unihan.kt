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

import io.zizai.han.reading.Pinyin
import io.zizai.han.reading.Reading
import java.io.BufferedReader
import java.io.File
import java.util.TreeMap
import java.util.zip.ZipFile

/** What the builder needs from Unihan about one character. */
class UnihanChar(val codePoint: Int) {
    /** Toned readings in source order, with their kHanyuPinlu count (0 when absent). */
    val readings = LinkedHashMap<Reading, Int>()

    /** Readings listed in kMandarin, the modern standard ones. */
    val mandarin = LinkedHashSet<Reading>()

    /** Position in the General Standard table (kTGH), 0 when not listed. */
    var tghIndex = 0
    var big5: Int = -1
    var gb2312 = false
    var strokes = 0
    val simplifiedVariants = ArrayList<Int>()
    val traditionalVariants = ArrayList<Int>()

    val tier: Int
        get() = when {
            tghIndex == 0 -> 0
            tghIndex <= 3500 -> 1
            tghIndex <= 6500 -> 2
            else -> 3
        }
}

/**
 * Reads the fields listed in docs/ARCHITECTURE.md §6.8 from `Unihan.zip`. Reading fields are merged in a
 * fixed order (kMandarin, kHanyuPinlu, kTGHZ2013, kXHC1983, kHanyuPinyin) so output is deterministic.
 */
object Unihan {
    private val READING_FIELDS = listOf("kMandarin", "kHanyuPinlu", "kTGHZ2013", "kXHC1983", "kHanyuPinyin")
    private val PINLU = Regex("""([^\s(]+)\((\d+)\)""")

    var unparsedReadings = 0
        private set

    fun read(zip: File): TreeMap<Int, UnihanChar> {
        val raw = TreeMap<Int, MutableMap<String, String>>()
        ZipFile(zip).use { z ->
            for (entry in z.entries().toList().sortedBy { it.name }) {
                z.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { collect(it, raw) }
            }
        }
        val out = TreeMap<Int, UnihanChar>()
        for ((cp, fields) in raw) {
            val c = UnihanChar(cp)
            for (field in READING_FIELDS) {
                val value = fields[field] ?: continue
                when (field) {
                    "kMandarin" -> value.split(' ').forEach { s -> parse(s)?.let { c.mandarin += it; c.readings.putIfAbsent(it, 0) } }
                    "kHanyuPinlu" -> PINLU.findAll(value).forEach { m ->
                        parse(m.groupValues[1])?.let { c.readings[it] = maxOf(c.readings[it] ?: 0, m.groupValues[2].toInt()) }
                    }
                    else -> value.split(' ').forEach { entry ->
                        entry.substringAfter(':').split(',').forEach { s -> parse(s)?.let { c.readings.putIfAbsent(it, 0) } }
                    }
                }
            }
            fields["kTGH"]?.let { v -> c.tghIndex = v.split(' ').first().substringAfter(':').toInt() }
            fields["kBigFive"]?.let { c.big5 = it.trimEnd('\'').toInt(16) } // a trailing ' marks a duplicate code
            c.gb2312 = "kGB0" in fields
            fields["kTotalStrokes"]?.let { c.strokes = it.split(' ').first().toInt() }
            fields["kSimplifiedVariant"]?.let { c.simplifiedVariants += codePoints(it) }
            fields["kTraditionalVariant"]?.let { c.traditionalVariants += codePoints(it) }
            if (c.readings.isNotEmpty() || c.tghIndex > 0) out[cp] = c
        }
        return out
    }

    private val WANTED = READING_FIELDS.toSet() +
        setOf("kTGH", "kBigFive", "kGB0", "kTotalStrokes", "kSimplifiedVariant", "kTraditionalVariant")

    private fun collect(reader: BufferedReader, raw: TreeMap<Int, MutableMap<String, String>>) {
        reader.forEachLine { line ->
            if (line.isEmpty() || line[0] == '#') return@forEachLine
            val tab1 = line.indexOf('\t')
            val tab2 = line.indexOf('\t', tab1 + 1)
            if (tab1 < 0 || tab2 < 0) return@forEachLine
            val field = line.substring(tab1 + 1, tab2)
            if (field !in WANTED) return@forEachLine
            val cp = line.substring(2, tab1).toInt(16)
            raw.getOrPut(cp) { HashMap() }[field] = line.substring(tab2 + 1)
        }
    }

    private fun parse(s: String): Reading? = Pinyin.parseToneMarked(s).also { if (it == null) unparsedReadings++ }

    private fun codePoints(v: String): List<Int> = v.split(' ').map { it.substringBefore('<').removePrefix("U+").toInt(16) }
}
