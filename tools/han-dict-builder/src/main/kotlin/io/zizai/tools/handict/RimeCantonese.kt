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

import java.io.File

/**
 * rime-cantonese by CanCLID (CC BY 4.0; https://github.com/rime/rime-cantonese), for Jyutping typing and
 * Cantonese flipping (docs/IMPLEMENTATION_PLAN.md P3-01). Only the CC BY files: never jyut6ping3.maps,
 * which is ODbL.
 *
 * - `jyut6ping3.chars.dict.yaml`: `字 TAB jyutping [TAB share%]`, each reading of a character with how
 *   often it is that reading.
 * - `jyut6ping3.words.dict.yaml`: `詞 TAB jyut ping` with one toned syllable per character.
 * - `essay-cantonese.txt`: `詞 TAB count`, word and character frequencies.
 */
class RimeCantonese(val chars: List<Char>, val words: List<Word>, val counts: Map<String, Long>) {
    class Char(val codePoint: Int, val syllable: String, val tone: Int, val share: Int?)
    class Word(val text: String, val syllables: List<Pair<String, Int>>)

    companion object {
        private val SYLLABLE = Regex("""([a-z]+)([1-6])""")

        fun read(chars: File, words: File, essay: File) = RimeCantonese(
            readChars(chars), readWords(words), readEssay(essay),
        )

        /** The part of a Rime dict after its YAML header. */
        private fun body(file: File): Sequence<String> {
            val text = file.readText(Charsets.UTF_8)
            val start = text.indexOf("\n...\n")
            return (if (start >= 0) text.substring(start + 5) else text).lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
        }

        private fun readChars(file: File) = body(file).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 2 || f[0].codePointCount(0, f[0].length) != 1) return@mapNotNull null
            val m = SYLLABLE.matchEntire(f[1].trim()) ?: return@mapNotNull null
            val share = f.getOrNull(2)?.trim()?.removeSuffix("%")?.toIntOrNull()
            Char(f[0].codePointAt(0), m.groupValues[1], m.groupValues[2].toInt(), share)
        }.toList()

        private fun readWords(file: File) = body(file).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 2) return@mapNotNull null
            val syllables = f[1].trim().split(' ').map { SYLLABLE.matchEntire(it) ?: return@mapNotNull null }
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
            if (syllables.size != f[0].codePointCount(0, f[0].length) || syllables.size < 2) return@mapNotNull null
            Word(f[0], syllables)
        }.toList()

        private fun readEssay(file: File): Map<String, Long> {
            val out = HashMap<String, Long>()
            for (line in file.readLines(Charsets.UTF_8)) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val n = line.substring(tab + 1).trim().toLongOrNull() ?: continue
                out[line.substring(0, tab)] = maxOf(out[line.substring(0, tab)] ?: 0, n)
            }
            return out
        }
    }
}
