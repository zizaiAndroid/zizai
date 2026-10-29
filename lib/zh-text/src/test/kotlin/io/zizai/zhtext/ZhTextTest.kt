/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.zhtext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ZhTextTest : FunSpec({
    test("Han script detection") {
        ZhText.isHan('字'.code) shouldBe true
        ZhText.isHan("𠀀".codePointAt(0)) shouldBe true
        ZhText.isHan('a'.code) shouldBe false
        ZhText.isHan('，'.code) shouldBe false
    }
})
