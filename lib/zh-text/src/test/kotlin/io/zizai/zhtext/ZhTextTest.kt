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
import java.io.File

val zhConvertFile = File("../../app/src/main/assets/han/zh-convert.bin")

class ZhTextTest : FunSpec({
    val converter = ZhConverter.open(zhConvertFile)

    // OpenCC 1.1.9's own test cases (test/testcases, Apache-2.0), one line per case.
    for ((name, script) in listOf(
        "s2t" to ZhScript.TRADITIONAL, "s2tw" to ZhScript.TAIWAN, "s2twp" to ZhScript.TAIWAN_PHRASES,
        "s2hk" to ZhScript.HONG_KONG, "t2s" to ZhScript.SIMPLIFIED,
    )) {
        test("OpenCC test cases: $name") {
            val input = javaClass.getResource("/opencc/$name.in")!!.readText().lines()
            val expected = javaClass.getResource("/opencc/$name.ans")!!.readText().lines()
            input.zip(expected).filter { it.first.isNotEmpty() }.forEach { (i, e) -> converter.convert(i, script) shouldBe e }
        }
    }

    test("conversion leaves non-Chinese text and supplementary characters alone") {
        converter.convert("Hello, 𠀀 world 123", ZhScript.TRADITIONAL) shouldBe "Hello, 𠀀 world 123"
        converter.convert("软件", ZhScript.AS_IS) shouldBe "软件"
        converter.convert("软件", ZhScript.TAIWAN_PHRASES) shouldBe "軟體"
        converter.convert("頭髮", ZhScript.SIMPLIFIED) shouldBe "头发"
    }

    test("Han script detection") {
        ZhText.isHan('字'.code) shouldBe true
        ZhText.isHan("𠀀".codePointAt(0)) shouldBe true
        ZhText.isHan('a'.code) shouldBe false
        ZhText.isHan('，'.code) shouldBe false
    }

    test("spaces between Han characters are removed, others kept") {
        ZhText.removeSpacesBetweenHan("你 好 ， 世界 。") shouldBe "你好，世界。"
        ZhText.removeSpacesBetweenHan("用 Kotlin 写") shouldBe "用 Kotlin 写"
        ZhText.removeSpacesBetweenHan("第一行\n第二行") shouldBe "第一行\n第二行"
    }

    test("punctuation after Chinese becomes full-width; numbers keep theirs") {
        ZhText.normalizePunctuation("你好, 世界. 真的? 好!", PunctuationLocale.MAINLAND) shouldBe "你好，世界。真的？好！"
        ZhText.normalizePunctuation("圆周率是3.14, 约1,000人", PunctuationLocale.MAINLAND) shouldBe "圆周率是3.14，约1,000人"
        ZhText.normalizePunctuation("十点10:30开会: 准时", PunctuationLocale.MAINLAND) shouldBe "十点10:30开会：准时"
        ZhText.normalizePunctuation("Hello, world.", PunctuationLocale.MAINLAND) shouldBe "Hello, world."
        ZhText.normalizePunctuation("等等...", PunctuationLocale.MAINLAND) shouldBe "等等……"
    }

    test("quotes follow the locale") {
        ZhText.normalizePunctuation("他说\"你好\"", PunctuationLocale.MAINLAND) shouldBe "他说“你好”"
        ZhText.normalizePunctuation("他說\"你好\"", PunctuationLocale.TAIWAN) shouldBe "他說「你好」"
        ZhText.normalizePunctuation("他說“你好”", PunctuationLocale.HONG_KONG) shouldBe "他說「你好」"
        ZhText.normalizePunctuation("他说「你好」", PunctuationLocale.MAINLAND) shouldBe "他说“你好”"
    }

    test("CJK-Latin spacing") {
        ZhText.cjkLatinSpacing("用Kotlin写2个app") shouldBe "用 Kotlin 写 2 个 app"
        ZhText.cjkLatinSpacing("中文，English") shouldBe "中文，English"
    }
})
