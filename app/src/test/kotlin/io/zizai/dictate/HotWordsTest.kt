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

import io.zizai.han.candidate.LearnedDictionary
import io.zizai.han.reading.Mandarin
import kotlin.test.Test
import kotlin.test.assertEquals

/** P2-07: the user's phrases join the transcription glossary for Chinese dictation only. */
class HotWordsTest {
    private fun ids(vararg s: String) = s.map { Mandarin.syllables.idOf(it) }.toIntArray()

    @Test
    fun `user phrases follow the custom words, without duplicates, for Chinese only`() {
        assertEquals("Kotlin, 张三丰, 微信群", HotWords.merge("Kotlin", listOf("张三丰", "微信群"), "zh-CN"))
        assertEquals("张三丰", HotWords.merge("", listOf("张三丰"), "zh-TW"))
        assertEquals("张三丰, 李四", HotWords.merge("张三丰", listOf("张三丰", "李四"), "yue-HK"))
        assertEquals("Kotlin", HotWords.merge("Kotlin", listOf("张三丰"), "en"))
        assertEquals("Kotlin", HotWords.merge("Kotlin", emptyList(), "zh-CN"))
    }

    @Test
    fun `the dictionary offers assembled phrases and words chosen twice, most used first`() {
        val d = LearnedDictionary { 0L }
        d.learn("张三丰", ids("zhang", "san", "feng"), assembled = true)
        d.learn("中国", ids("zhong", "guo"), assembled = false) // once: not yet the user's
        d.learn("微信", ids("wei", "xin"), assembled = false)
        d.learn("微信", ids("wei", "xin"), assembled = false)
        d.learn("微信", ids("wei", "xin"), assembled = false)
        d.learn("是", ids("shi"), assembled = false)
        d.learn("是", ids("shi"), assembled = false) // a single character is no hot word
        assertEquals(listOf("微信", "张三丰"), d.topPhrases(10))
        d.block("微信")
        assertEquals(listOf("张三丰"), d.topPhrases(10))
    }
}
