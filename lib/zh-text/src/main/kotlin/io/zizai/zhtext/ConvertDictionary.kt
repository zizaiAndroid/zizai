/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.zhtext

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Layout of `zh-convert.bin`, written by `:tools:han-dict-builder` from OpenCC's dictionaries. Big-endian,
 * sections on 4-byte boundaries.
 *
 * ```text
 * header   "ZZCV", u32 version, u32 dictCount, u32 reserved, dictCount × { u32 tag, u32 offset, u32 length }
 * dict     u32 n, u32 maxKeyLength, n × 12 bytes { u32 keyOffset, u16 keyLength, u16 valueLength,
 *          u32 valueOffset }, sorted by key (UTF-16 order); then the UTF-16 text all offsets point into
 * ```
 */
object ConvertFormat {
    const val MAGIC = 0x5A5A4356 // "ZZCV"
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val ENTRY_SIZE = 12

    /** OpenCC dictionary names and their tags. TWPhrases merges TWPhrasesIT, -Name and -Other. */
    val TAGS = mapOf(
        "STPhrases" to tag("STPH"), "STCharacters" to tag("STCH"),
        "TSPhrases" to tag("TSPH"), "TSCharacters" to tag("TSCH"),
        "TWVariants" to tag("TWVA"), "TWPhrases" to tag("TWPH"), "HKVariants" to tag("HKVA"),
    )

    fun tag(name: String): Int = name.fold(0) { acc, c -> (acc shl 8) or c.code }
}

/** One OpenCC dictionary inside `zh-convert.bin`, searched in place. */
class ConvertDictionary internal constructor(private val buf: ByteBuffer, private val base: Int) {
    val size: Int = buf.getInt(base)
    val maxKeyLength: Int = buf.getInt(base + 4)
    private val entries = base + 8
    private val text = entries + size * ConvertFormat.ENTRY_SIZE

    /** Index of the longest key that [s] has at [start] (not past [end]), with its length; or -1. */
    fun longestPrefix(s: CharSequence, start: Int, end: Int): Long {
        var len = minOf(maxKeyLength, end - start)
        while (len > 0) {
            val i = find(s, start, len)
            if (i >= 0) return (i.toLong() shl 32) or len.toLong()
            len--
        }
        return -1
    }

    fun value(index: Int): String {
        val e = entries + index * ConvertFormat.ENTRY_SIZE
        val off = buf.getInt(e + 8)
        val len = buf.getShort(e + 6).toInt() and 0xFFFF
        return String(CharArray(len) { buf.getChar(text + (off + it) * 2) })
    }

    private fun find(s: CharSequence, start: Int, len: Int): Int {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(mid, s, start, len)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun compare(index: Int, s: CharSequence, start: Int, len: Int): Int {
        val e = entries + index * ConvertFormat.ENTRY_SIZE
        val off = buf.getInt(e)
        val keyLen = buf.getShort(e + 4).toInt() and 0xFFFF
        for (i in 0 until minOf(keyLen, len)) {
            val c = buf.getChar(text + (off + i) * 2) - s[start + i]
            if (c != 0) return c
        }
        return keyLen - len
    }

    companion object {
        /** Reads every dictionary in a `zh-convert.bin` buffer, by OpenCC name. */
        fun readAll(buffer: ByteBuffer): Map<String, ConvertDictionary> {
            val buf = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
            check(buf.getInt(0) == ConvertFormat.MAGIC) { "not a zh-convert file" }
            check(buf.getInt(4) == ConvertFormat.VERSION) { "zh-convert version ${buf.getInt(4)}" }
            val byTag = ConvertFormat.TAGS.entries.associate { (k, v) -> v to k }
            val out = HashMap<String, ConvertDictionary>()
            for (i in 0 until buf.getInt(8)) {
                val at = ConvertFormat.HEADER_SIZE + i * 12
                val name = byTag[buf.getInt(at)] ?: continue
                out[name] = ConvertDictionary(buf, buf.getInt(at + 4))
            }
            return out
        }
    }
}
