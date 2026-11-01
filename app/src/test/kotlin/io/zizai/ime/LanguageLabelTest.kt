/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ime

import kotlin.test.Test
import kotlin.test.assertEquals

/** The language key names the active language. */
class LanguageLabelTest {
    private fun label(tag: String) = ZizaiIme.shortLanguageLabel(tag)

    @Test
    fun `Chinese, Cantonese, Japanese and Korean use their own characters`() {
        assertEquals("中", label("zh-CN-pinyin"))
        assertEquals("中", label("zh-TW"))
        assertEquals("粵", label("yue"))
        assertEquals("粵", label("zh-HK"))
        assertEquals("あ", label("ja-JP"))
        assertEquals("한", label("ko-KR"))
    }

    @Test
    fun `other languages show their code`() {
        assertEquals("EN", label("en-US"))
        assertEquals("EN", label("en-UK"))
        assertEquals("DE", label("de-DE"))
        assertEquals("FR", label("fr"))
    }
}
