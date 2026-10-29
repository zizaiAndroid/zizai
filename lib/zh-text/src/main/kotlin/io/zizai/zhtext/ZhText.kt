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

/** Quote and punctuation conventions. Taiwan and Hong Kong use corner brackets for quotes. */
enum class PunctuationLocale { MAINLAND, TAIWAN, HONG_KONG }

/**
 * Text clean-up for Chinese (docs/IMPLEMENTATION_PLAN.md P1-09): what speech recognizers get wrong in
 * Chinese text, independent of the script.
 */
object ZhText {
    /** True for a code point in the Han script (CJK Unified Ideographs and their extensions). */
    fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    /** CJK symbols and punctuation, and full-width forms (，。！ and the like). */
    fun isCjkPunctuation(c: Char): Boolean = c in '　'..'〿' || c in '＀'..'￯' || c in "“”‘’…—·"

    private fun isCjk(c: Char) = isHan(c.code) || Character.isSurrogate(c) || isCjkPunctuation(c)

    /** Removes spaces (not line breaks) between two Han characters or CJK punctuation: `你 好 。` → `你好。`. */
    fun removeSpacesBetweenHan(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '　' || c == '\t') {
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '　' || text[j] == '\t')) j++
                val prev = out.lastOrNull()
                val next = text.getOrNull(j)
                if (prev != null && next != null && isCjk(prev) && isCjk(next)) {
                    i = j
                    continue
                }
                out.append(text, i, j)
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * Turns ASCII punctuation that follows Chinese text into full-width punctuation, drops the space a
     * recognizer leaves after it, and applies the locale's quotes. Numbers keep `3.14`, `1,000` and `10:30`.
     */
    fun normalizePunctuation(text: String, locale: PunctuationLocale): String {
        val corner = locale != PunctuationLocale.MAINLAND
        val out = StringBuilder(text.length)
        var doubleOpen = true
        var sentenceHasHan = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c.code) || Character.isSurrogate(c)) sentenceHasHan = true
            val prev = out.lastOrNull()
            val afterCjk = prev != null && isCjk(prev)
            var j = i + 1
            while (text.getOrNull(j) == ' ') j++
            val beforeCjk = text.getOrNull(j)?.let(::isCjk) == true
            val betweenDigits = prev?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true
            // A Chinese sentence that ends on a Latin word still ends with 。: `参加Google I/O.`
            val endsChineseSentence = sentenceHasHan && c in ".?!" &&
                text.getOrNull(i + 1).let { it == null || it.isWhitespace() }
            val mapped: String? = when {
                c == '.' && text.startsWith("...", i) && afterCjk -> { i += 2; "……" }
                !(afterCjk || endsChineseSentence || (beforeCjk && c != '(')) || betweenDigits -> null
                c == ',' -> "，"
                c == '.' -> "。"
                c == '?' -> "？"
                c == '!' -> "！"
                c == ':' -> "："
                c == ';' -> "；"
                c == '(' -> "（"
                c == ')' -> "）"
                else -> null
            }
            if (mapped != null) {
                if (mapped in SENTENCE_ENDS) sentenceHasHan = false
                out.append(mapped)
                i++
                if (text.getOrNull(i) == ' ') i++
                continue
            }
            val nextIsCjk = text.getOrNull(i + 1)?.let(::isCjk) == true
            if (c == '"' && (afterCjk || nextIsCjk)) {
                out.append(if (corner) (if (doubleOpen) '「' else '」') else (if (doubleOpen) '“' else '”'))
                doubleOpen = !doubleOpen
                i++
                continue
            }
            out.append(
                when {
                    corner && c == '“' -> '「'
                    corner && c == '”' -> '」'
                    corner && c == '‘' -> '『'
                    corner && c == '’' -> '』'
                    !corner && c == '「' -> '“'
                    !corner && c == '」' -> '”'
                    !corner && c == '『' -> '‘'
                    !corner && c == '』' -> '’'
                    else -> c
                },
            )
            i++
        }
        return out.toString()
    }

    /** Puts a space between Han characters and Latin letters or digits: `用Kotlin写` → `用 Kotlin 写`. */
    fun cjkLatinSpacing(text: String): String {
        val out = StringBuilder(text.length + 8)
        var prevCp = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (prevCp >= 0) {
                val a = isHan(prevCp)
                val b = isHan(cp)
                if ((a && isLatinOrDigit(cp)) || (b && isLatinOrDigit(prevCp))) out.append(' ')
            }
            out.appendCodePoint(cp)
            prevCp = cp
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private val SENTENCE_ENDS = setOf("。", "？", "！", "……")

    private fun isLatinOrDigit(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code
}
