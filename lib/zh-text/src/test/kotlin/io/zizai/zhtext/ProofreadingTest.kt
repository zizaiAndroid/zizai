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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** P2-08: model output is validated against the text; anything invalid is dropped. */
class ProofreadingTest : FunSpec({
    val text = "我们明天在见吧，他的很好。"

    test("a valid suspect is kept, with UTF-16 offsets") {
        val s = Proofreading.parse(
            text, """{"suspects":[{"start":4,"end":5,"original":"在","suggestions":["再"],"confidence":0.9,"hint":"再见"}]}""",
        ).single()
        s.start shouldBe 4
        s.end shouldBe 5
        s.suggestions shouldBe listOf("再")
        s.hint shouldBe "再见"
    }

    test("code-fenced answers and prose around the JSON still parse") {
        Proofreading.parse(text, "Here you go:\n```json\n{\"suspects\":[{\"start\":4,\"end\":5,\"original\":\"在\",\"suggestions\":[\"再\"]}]}\n```")
            .single().original shouldBe "在"
    }

    test("wrong originals, bad spans, empty or non-Chinese suggestions are dropped") {
        Proofreading.parse(
            text,
            """{"suspects":[
              {"start":4,"end":5,"original":"再","suggestions":["在"]},
              {"start":40,"end":41,"original":"在","suggestions":["再"]},
              {"start":5,"end":4,"original":"","suggestions":["再"]},
              {"start":4,"end":5,"original":"在","suggestions":[]},
              {"start":4,"end":5,"original":"在","suggestions":["zai", "在"]}
            ]}""",
        ).shouldBeEmpty()
        Proofreading.parse(text, "not json").shouldBeEmpty()
        Proofreading.parse(text, """{"answer":"再见"}""").shouldBeEmpty()
    }

    test("overlaps keep the first, the list is sorted and capped") {
        val found = Proofreading.parse(
            text,
            """{"suspects":[
              {"start":8,"end":10,"original":"他的","suggestions":["她的"]},
              {"start":4,"end":5,"original":"在","suggestions":["再"]},
              {"start":9,"end":10,"original":"的","suggestions":["得"]}
            ]}""",
        )
        found.map { it.original } shouldBe listOf("在", "他的")
        val many = (0 until 30).joinToString(",") { """{"start":4,"end":5,"original":"在","suggestions":["再"]}""" }
        Proofreading.parse(text, """{"suspects":[$many]}""").size shouldBe 1
    }

    test("code points: a supplementary character before the span shifts UTF-16 offsets") {
        val t = "𠀀在见"
        val s = Proofreading.parse(t, """{"suspects":[{"start":1,"end":2,"original":"在","suggestions":["再"]}]}""").single()
        t.substring(s.start, s.end) shouldBe "在"
        s.start shouldBe 2
    }
})
