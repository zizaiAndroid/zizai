/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HanEngineTest : FunSpec({
    test("module is wired up") {
        HanEngine.LEXICON_FORMAT shouldBe 1
    }
})
