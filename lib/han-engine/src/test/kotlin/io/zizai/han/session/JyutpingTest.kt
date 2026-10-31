/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.session

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.zizai.han.candidate.HomophoneFilter
import io.zizai.han.candidate.ScriptFilter
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.parse.JyutpingParser
import io.zizai.han.reading.Cantonese
import java.io.File

/** P3-01: typing Cantonese in Jyutping with the bundled rime-cantonese data. */
class JyutpingTest : FunSpec({
    val yue = LexiconReader.open(File("../../app/src/main/assets/han/han-yue.bin"), Cantonese)

    fun top(input: String, n: Int = 5, lazy: Boolean = false): List<String> {
        val editor = FakeEditor()
        // Cantonese is written in Traditional characters, so the script filter lets both through.
        val session = DefaultHanSession(
            yue, settings = { HanSettings(lazySounds = lazy, filter = HomophoneFilter(script = ScriptFilter.BOTH)) },
            editor = editor,
        )
        for (c in input) {
            val k = KeyStroke.char(c)
            editor.apply(session.onKey(k), k)
        }
        return session.ui.value.candidates.take(n).map { it.text }
    }

    // Everyday Cantonese: input, a word in the top five.
    val golden = listOf(
        "neihou" to "你好", "ngodei" to "我哋", "mgoi" to "唔該", "doze" to "多謝", "haimai" to "係咪",
        "gamjat" to "今日", "tingjat" to "聽日", "jausam" to "有心", "sikfaan" to "食飯", "heoigaai" to "去街",
        "hai" to "係", "hai" to "喺", "ge" to "嘅", "keoi" to "佢", "m" to "唔",
    )

    test("everyday Cantonese words and characters come up") {
        val misses = golden.filterNot { (input, word) -> word in top(input) }
        withClue(misses.map { (i, w) -> "$i → $w: ${top(i)}" }) { misses.size shouldBe 0 }
    }

    test("initials stand for whole syllables") {
        top("ngd") shouldContain "我哋"
        top("mg") shouldContain "唔該"
    }

    test("lazy sounds find the standard readings only when on") {
        top("leihou", lazy = true) shouldContain "你好"
        (("你好" in top("leihou", lazy = false))) shouldBe false
        JyutpingParser.lazyVariants("o").toSet() shouldContain "ngo"
        JyutpingParser.lazyVariants("gok").toSet() shouldContain "gwok"
        JyutpingParser.lazyVariants("hon").toSet() shouldContain "hong"
    }
})
