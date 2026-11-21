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

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class FloatingMicTest {
    @Test
    fun `the button is kept wholly on the screen`() {
        assertEquals(IntOffset(100, 200), FloatingMic.clamp(100, 200, 160, 1008, 2244))
        assertEquals(IntOffset(0, 0), FloatingMic.clamp(-50, -10, 160, 1008, 2244))
        assertEquals(IntOffset(848, 2084), FloatingMic.clamp(5000, 9000, 160, 1008, 2244))
    }
}
