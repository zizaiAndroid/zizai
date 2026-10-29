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

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Output scripts, named after OpenCC's configurations. */
enum class ZhScript {
    /** Leave the text as it is. */
    AS_IS,

    /** Traditional → Simplified (`t2s`). */
    SIMPLIFIED,

    /** Simplified → Traditional, OpenCC standard (`s2t`). */
    TRADITIONAL,

    /** Simplified → Traditional, Taiwan characters (`s2tw`). */
    TAIWAN,

    /** Simplified → Traditional, Taiwan characters and phrases (`s2twp`): 软件 → 軟體. */
    TAIWAN_PHRASES,

    /** Simplified → Traditional, Hong Kong characters (`s2hk`). */
    HONG_KONG,
}

/**
 * Converts between Simplified and Traditional Chinese the way OpenCC 1.1 does: segment by maximum
 * forward matching on the phrase dictionary, then run each segment through the conversion chain, where
 * each step takes the longest match from the first dictionary of its group that has one.
 */
class ZhConverter(dictionaries: Map<String, ConvertDictionary>) {
    private val d = dictionaries

    private class Config(val segmentation: ConvertDictionary, val chain: List<List<ConvertDictionary>>)

    private val configs: Map<ZhScript, Config> by lazy {
        fun g(vararg names: String) = names.map { d[it] ?: error("zh-convert has no $it") }
        val st = g("STPhrases", "STCharacters")
        mapOf(
            ZhScript.SIMPLIFIED to Config(g("TSPhrases")[0], listOf(g("TSPhrases", "TSCharacters"))),
            ZhScript.TRADITIONAL to Config(st[0], listOf(st)),
            ZhScript.TAIWAN to Config(st[0], listOf(st, g("TWVariants"))),
            ZhScript.TAIWAN_PHRASES to Config(st[0], listOf(st, g("TWPhrases"), g("TWVariants"))),
            ZhScript.HONG_KONG to Config(st[0], listOf(st, g("HKVariants"))),
        )
    }

    fun convert(text: String, script: ZhScript): String {
        if (script == ZhScript.AS_IS || text.isEmpty()) return text
        val config = configs.getValue(script)
        val out = StringBuilder(text.length)
        // As OpenCC's MaxMatchSegmentation: each phrase match is a segment, and each run of characters
        // between matches is one segment too, so later steps can match across it (鼠标 → 鼠標 → 滑鼠).
        var runStart = 0
        var pos = 0
        fun flush(until: Int) {
            if (until > runStart) out.append(convertChain(text.substring(runStart, until), config))
        }
        while (pos < text.length) {
            val m = config.segmentation.longestPrefix(text, pos, text.length)
            if (m >= 0) {
                flush(pos)
                val len = (m and 0xFFFFFFFF).toInt()
                out.append(convertChain(text.substring(pos, pos + len), config))
                pos += len
                runStart = pos
            } else {
                pos += Character.charCount(text.codePointAt(pos))
            }
        }
        flush(pos)
        return out.toString()
    }

    private fun convertChain(segment: String, config: Config): String {
        var s = segment
        for (step in config.chain) s = convertSegment(s, step)
        return s
    }

    private fun convertSegment(s: String, group: List<ConvertDictionary>): String {
        val out = StringBuilder(s.length)
        var pos = 0
        while (pos < s.length) {
            var matched = false
            for (dict in group) {
                val m = dict.longestPrefix(s, pos, s.length)
                if (m >= 0) {
                    out.append(dict.value((m ushr 32).toInt()))
                    pos += (m and 0xFFFFFFFF).toInt()
                    matched = true
                    break
                }
            }
            if (!matched) {
                val n = Character.charCount(s.codePointAt(pos))
                out.append(s, pos, pos + n)
                pos += n
            }
        }
        return out.toString()
    }

    companion object {
        fun open(buffer: ByteBuffer) = ZhConverter(ConvertDictionary.readAll(buffer))

        fun open(file: File): ZhConverter = RandomAccessFile(file, "r").use {
            open(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()))
        }
    }
}
