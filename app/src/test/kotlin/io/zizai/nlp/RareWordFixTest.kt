/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RareWordFixTest {
    // Frequencies as in the bundled en.json.
    private val freq = mapOf(
        "the" to 255, "are" to 254, "and" to 255, "her" to 240, "had" to 245, "ten" to 215,
        "aer" to 145, "teh" to 155, "nad" to 163, "aerial" to 201,
        "form" to 238, "from" to 252, "tow" to 191, "two" to 245,
    )
    private val alphabet = ('a'..'z').toSet()

    private fun fix(word: String) = RareWordFix.fixFor(word, freq, alphabet)

    @Test
    fun `swapped letters spelling a rare word get the common word`() {
        assertEquals("are", fix("aer"))
        assertEquals("the", fix("teh"))
        assertEquals("and", fix("nad"))
    }

    @Test
    fun `common words are left alone`() {
        assertNull(fix("form"))
        assertNull(fix("tow"))
        assertNull(fix("are"))
    }

    @Test
    fun `unknown and short words are not this rule's business`() {
        assertNull(fix("xyzq"))
        assertNull(fix("ot"))
    }
}
