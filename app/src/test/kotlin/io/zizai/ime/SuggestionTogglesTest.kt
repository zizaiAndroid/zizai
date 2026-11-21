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

import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.zizai.ime.SuggestionToggles.AUTOCORRECT
import io.zizai.ime.SuggestionToggles.CODE_AUTOCORRECT
import io.zizai.ime.SuggestionToggles.CODE_COMPLETIONS
import io.zizai.ime.SuggestionToggles.COMPLETIONS
import io.zizai.ime.SuggestionToggles.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SuggestionTogglesTest {
    private fun tap(completions: Boolean, autoCorrect: Boolean, code: Int) =
        SuggestionToggles.afterTap(State(completions, autoCorrect), code)

    @Test
    fun `the completions button flips completions and leaves autocorrect alone`() {
        assertEquals(State(false, true), tap(true, true, CODE_COMPLETIONS))
        assertEquals(State(true, true), tap(false, true, CODE_COMPLETIONS))
        assertEquals(State(true, false), tap(false, false, CODE_COMPLETIONS))
    }

    @Test
    fun `the autocorrect button turns off only autocorrect`() {
        assertEquals(State(true, false), tap(true, true, CODE_AUTOCORRECT))
    }

    @Test
    fun `turning autocorrect on also turns completions on, which it runs through`() {
        assertEquals(State(true, true), tap(true, false, CODE_AUTOCORRECT))
        assertEquals(State(true, true), tap(false, false, CODE_AUTOCORRECT))
        // Reads "off" while completions are off, so a tap turns it on rather than off.
        assertEquals(State(true, true), tap(false, true, CODE_AUTOCORRECT))
    }

    private val incognito = QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE)
    private val undo = QuickAction.InsertKey(TextKeyData.UNDO)
    private val redo = QuickAction.InsertKey(TextKeyData.REDO)

    @Test
    fun `the buttons go in after incognito`() {
        val before = QuickActionArrangement(null, listOf(undo, incognito, redo), emptyList())
        assertEquals(
            listOf(undo, incognito, COMPLETIONS, AUTOCORRECT, redo),
            SuggestionToggles.withButtons(before).dynamicActions,
        )
    }

    @Test
    fun `without incognito in the list they go at the end`() {
        val before = QuickActionArrangement(null, listOf(undo), listOf(incognito))
        assertEquals(listOf(undo, COMPLETIONS, AUTOCORRECT), SuggestionToggles.withButtons(before).dynamicActions)
    }

    @Test
    fun `a button the user hid stays hidden`() {
        val before = QuickActionArrangement(null, listOf(undo, incognito), listOf(COMPLETIONS))
        val after = SuggestionToggles.withButtons(before)
        assertEquals(listOf(undo, incognito, AUTOCORRECT), after.dynamicActions)
        assertEquals(listOf(COMPLETIONS), after.hiddenActions)
        assertSame(after, SuggestionToggles.withButtons(after))
    }
}
