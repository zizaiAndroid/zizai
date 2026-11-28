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

import io.zizai.ime.HardwareShortcuts.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareShortcutsTest {
    private val en = Language(1, chinese = false)
    private val zh = Language(2, chinese = true)
    private val fr = Language(3, chinese = false)
    private val yue = Language(4, chinese = true)

    @Test
    fun `Shift goes back and forth between English and Chinese`() {
        assertEquals(zh.id, HardwareShortcuts.shiftTarget(en, listOf(en, zh), null))
        assertEquals(en.id, HardwareShortcuts.shiftTarget(zh, listOf(en, zh), en.id))
    }

    @Test
    fun `with Chinese alone there is nothing to switch to`() {
        assertNull(HardwareShortcuts.shiftTarget(zh, listOf(zh), null))
    }

    @Test
    fun `it prefers the other side of the pair, the one it last left first`() {
        val all = listOf(en, fr, zh, yue)
        assertEquals(zh.id, HardwareShortcuts.shiftTarget(en, all, null))
        assertEquals(yue.id, HardwareShortcuts.shiftTarget(fr, all, yue.id))
        // Left French for Chinese: back to French, not the first in the list.
        assertEquals(fr.id, HardwareShortcuts.shiftTarget(zh, all, fr.id))
        // What it left is on the same side: the other side still wins.
        assertEquals(en.id, HardwareShortcuts.shiftTarget(zh, all, yue.id))
    }

    @Test
    fun `without Chinese it pairs with the language it left`() {
        assertEquals(fr.id, HardwareShortcuts.shiftTarget(en, listOf(en, fr), null))
        assertEquals(en.id, HardwareShortcuts.shiftTarget(fr, listOf(en, fr, Language(5, false)), en.id))
    }

    @Test
    fun `digits pick words one to nine, then zero for the tenth`() {
        assertEquals(0, HardwareShortcuts.digitIndex('1'.code))
        assertEquals(8, HardwareShortcuts.digitIndex('9'.code))
        assertEquals(9, HardwareShortcuts.digitIndex('0'.code))
        assertNull(HardwareShortcuts.digitIndex('a'.code))
    }

    @Test
    fun `Chinese tags`() {
        assertTrue(HardwareShortcuts.isChineseTag("zh-CN-pinyin"))
        assertTrue(HardwareShortcuts.isChineseTag("yue-HK"))
        assertFalse(HardwareShortcuts.isChineseTag("en-US"))
    }
}
