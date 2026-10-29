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

import io.zizai.zhtext.ConvertFormat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.TreeMap

/** Compiles OpenCC text dictionaries (`key<TAB>value [value…]`) into `zh-convert.bin` (layout on [ConvertFormat]). */
object OpenCc {
    /** OpenCC dictionary name → source names in sources.lock, merged in order (first key wins). */
    val DICTIONARIES = linkedMapOf(
        "STPhrases" to listOf("opencc-STPhrases"),
        "STCharacters" to listOf("opencc-STCharacters"),
        "TSPhrases" to listOf("opencc-TSPhrases"),
        "TSCharacters" to listOf("opencc-TSCharacters"),
        "TWVariants" to listOf("opencc-TWVariants"),
        "TWPhrases" to listOf("opencc-TWPhrasesIT", "opencc-TWPhrasesName", "opencc-TWPhrasesOther"),
        "HKVariants" to listOf("opencc-HKVariants"),
    )

    /** Keys to their default (first) value, sorted by key. */
    fun read(files: List<File>): TreeMap<String, String> {
        val out = TreeMap<String, String>()
        for (f in files) for (line in f.readLines(Charsets.UTF_8)) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val key = line.substring(0, tab)
            val value = line.substring(tab + 1).trim().split(' ').first()
            out.putIfAbsent(key, value)
        }
        return out
    }

    fun write(dicts: Map<String, TreeMap<String, String>>): ByteArray {
        val sections = dicts.map { (name, entries) -> ConvertFormat.TAGS.getValue(name) to dictSection(entries) }
        val result = ByteArrayOutputStream()
        val out = DataOutputStream(result)
        out.writeInt(ConvertFormat.MAGIC)
        out.writeInt(ConvertFormat.VERSION)
        out.writeInt(sections.size)
        out.writeInt(0)
        var offset = ConvertFormat.HEADER_SIZE + sections.size * 12
        for ((tag, bytes) in sections) {
            out.writeInt(tag)
            out.writeInt(offset)
            out.writeInt(bytes.size)
            offset += bytes.size // sections are multiples of 4 bytes
        }
        for ((_, bytes) in sections) out.write(bytes)
        return result.toByteArray()
    }

    private fun dictSection(entries: TreeMap<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(entries.size)
        out.writeInt(entries.keys.maxOf { it.length })
        var at = 0
        for ((k, v) in entries) {
            out.writeInt(at)
            out.writeShort(k.length)
            out.writeShort(v.length)
            out.writeInt(at + k.length)
            at += k.length + v.length
        }
        for ((k, v) in entries) {
            out.writeChars(k)
            out.writeChars(v)
        }
        while (bytes.size() % 4 != 0) out.writeByte(0)
        return bytes.toByteArray()
    }
}
