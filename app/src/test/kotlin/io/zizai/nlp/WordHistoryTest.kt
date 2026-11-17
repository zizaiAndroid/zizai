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

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class WordHistoryTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun scoreHalvesEveryThirtyDays() {
        assertEquals(4.0, WordHistory.decayedScore(4.0, 0), absoluteTolerance = 1e-9)
        assertEquals(2.0, WordHistory.decayedScore(4.0, 30 * day), absoluteTolerance = 1e-9)
        assertEquals(1.0, WordHistory.decayedScore(4.0, 60 * day), absoluteTolerance = 1e-9)
    }

    @Test
    fun recentUseBeatsOldHeavyUse() {
        // Typed once today vs. three times half a year ago.
        assertTrue(WordHistory.decayedScore(1.0, 0) > WordHistory.decayedScore(3.0, 180 * day))
    }

    @Test
    fun clockGoingBackwardsDoesNotInflate() {
        assertEquals(2.0, WordHistory.decayedScore(2.0, -5 * day), absoluteTolerance = 1e-9)
    }
}
