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

import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Pinyin
import java.io.File

/** One AOSP PinyinIME entry: a character or word, its frequency and its toneless syllable ids. */
class AospEntry(val text: String, val frequency: Double, val syllables: IntArray)

/**
 * Reads AOSP PinyinIME's `rawdict_utf16_65105_freq.txt`: UTF-16 lines of `text frequency flag syllables…`.
 * Entries whose syllables aren't in the inventory, or don't match the text's length, are skipped.
 */
object AospDict {
    var skipped = 0
        private set

    fun read(file: File): List<AospEntry> {
        val text = file.readBytes().toString(Charsets.UTF_16)
        val out = ArrayList<AospEntry>(65_200)
        for (line in text.lineSequence()) {
            val f = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (f.size < 4) continue
            val word = f[0]
            val ids = f.drop(3).map { Mandarin.syllables.idOf(Pinyin.normalizeSpelling(it)) }
            if (ids.any { it < 0 } || ids.size != word.codePointCount(0, word.length)) {
                skipped++
                continue
            }
            out += AospEntry(word, f[1].toDouble(), ids.toIntArray())
        }
        return out
    }
}
