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

import dev.patrickgold.florisboard.ime.editor.InputAttributes.Variation
import dev.patrickgold.florisboard.ime.editor.mayIgnoreNoSuggestionsFlag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestionBlockTest {

    private fun mayIgnore(variation: Variation) =
        mayIgnoreNoSuggestionsFlag(ignoreAppSuggestionBlock = true, variation = variation)

    @Test
    fun `prose fields that block suggestions get them anyway`() {
        // Gemini's prompt box, chat apps, WebViews.
        for (variation in listOf(Variation.NORMAL, Variation.LONG_MESSAGE, Variation.SHORT_MESSAGE, Variation.WEB_EDIT_TEXT)) {
            assertTrue(mayIgnore(variation), "$variation should be overruled")
        }
    }

    @Test
    fun `address and password fields keep the app's block`() {
        for (variation in listOf(
            Variation.EMAIL_ADDRESS,
            Variation.WEB_EMAIL_ADDRESS,
            Variation.URI,
            Variation.PASSWORD,
            Variation.VISIBLE_PASSWORD,
            Variation.WEB_PASSWORD,
        )) {
            assertFalse(mayIgnore(variation), "$variation must stay blocked")
        }
    }
}
