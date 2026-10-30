/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.dictate

import io.zizai.ime.HanUserDictionary

/**
 * Hot-word biasing from the user dictionary (docs/IMPLEMENTATION_PLAN.md P2-07): the Chinese words and
 * phrases the user typed most go into the transcription prompt's glossary, after their own custom words,
 * so names they type also come out right when they dictate them. Only for Chinese dictation, and capped,
 * so the prompt stays inside upstream's token budget.
 */
object HotWords {
    const val MAX_PHRASES = 30

    /** The hook: [customWords] as upstream stores them, plus the user's phrases for Chinese dictation. */
    fun withUserPhrases(customWords: String, language: String): String =
        merge(customWords, HanUserDictionary.loaded()?.topPhrases(MAX_PHRASES).orEmpty(), language)

    fun merge(customWords: String, phrases: List<String>, language: String): String {
        if (phrases.isEmpty() || !isChinese(language)) return customWords
        val existing = customWords.split(',', '\n').map { it.trim() }.toSet()
        val added = phrases.filter { it !in existing }.take(MAX_PHRASES)
        if (added.isEmpty()) return customWords
        return listOf(customWords.trim().trimEnd(','), added.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun isChinese(language: String) = language.startsWith("zh") || language.startsWith("yue")
}
