/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.session

/**
 * Full-width Chinese punctuation while idle (docs/ARCHITECTURE.md §6.2, "Chinese punctuation"). Paired
 * quotes alternate between opening and closing per session.
 */
class Punctuation(private val style: () -> PunctuationStyle) {
    private var doubleOpen = true
    private var singleOpen = true

    /** Full-width output for [c], or null to let the character through unchanged. */
    fun convert(c: Char, before: CharSequence?): String? = when (c) {
        ',' -> "，"
        '.' -> if (before?.lastOrNull()?.isDigit() == true) null else "。"
        '?' -> "？"
        '!' -> "！"
        ':' -> "："
        ';' -> "；"
        '\\' -> "、"
        '^' -> "……"
        '_' -> "——"
        '<' -> "《"
        '>' -> "》"
        '(' -> "（"
        ')' -> "）"
        '[' -> "【"
        ']' -> "】"
        '`' -> "·"
        '$' -> "￥"
        '~' -> "～"
        '"' -> quote(double = true)
        '\'' -> quote(double = false)
        else -> null
    }

    fun reset() {
        doubleOpen = true
        singleOpen = true
    }

    private fun quote(double: Boolean): String {
        val traditional = style() == PunctuationStyle.TRADITIONAL
        return if (double) {
            (if (traditional) (if (doubleOpen) "「" else "」") else (if (doubleOpen) "“" else "”")).also { doubleOpen = !doubleOpen }
        } else {
            (if (traditional) (if (singleOpen) "『" else "』") else (if (singleOpen) "‘" else "’")).also { singleOpen = !singleOpen }
        }
    }
}
