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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile
import io.zizai.han.reading.Cantonese
import io.zizai.han.reading.Mandarin
import java.io.File

/** P2-10: with Cantonese dictation, the flip offers Cantonese homophones. */
class CantoneseFlipTest : FunSpec({
    val mandarin = LexiconReader.open(coreLexiconFile)
    val yue = LexiconReader.open(File("../../app/src/main/assets/han/han-yue.bin"), Cantonese)

    fun options(text: String, cantonese: Boolean, pages: Int = 2): List<String> {
        val editor = FakeEditor()
        val session = DefaultHanSession(
            mandarin, settings = { HanSettings(flipCantonese = cantonese, filter = io.zizai.han.candidate.HomophoneFilter(script = io.zizai.han.candidate.ScriptFilter.BOTH)) },
            editor = editor, cantonese = yue,
        )
        editor.text.append(text); editor.cursor = text.length
        val s = KeyStroke.of(Key.FLIP)
        editor.apply(session.onKey(s), s)
        val out = ArrayList<String>()
        repeat(pages) {
            out += session.ui.value.candidates.map { it.text }
            val pg = KeyStroke.of(Key.PAGE_DOWN)
            editor.apply(session.onKey(pg), pg)
        }
        return out
    }

    test("the Cantonese table reads the bundled file") {
        yue.phonology shouldBe Cantonese
        yue.charCount shouldBe 29936
        Cantonese.syllables.size shouldBe 659
    }

    test("common Cantonese homophone errors flip to the right character") {
        // 喺 (hai2, at/in) and 係 (hai6, is) are the classic pair; 佢 (keoi5, he/she) and 距 share keoi5.
        withClue("係") { options("係", cantonese = true) shouldContain "喺" }
        withClue("喺") { options("喺", cantonese = true) shouldContain "係" }
        withClue("距") { options("距", cantonese = true, pages = 3) shouldContain "佢" }
        withClue("冇") { options("母", cantonese = true) shouldContain "冇" } // mou5
    }

    test("Mandarin flipping is unchanged when dictation isn't Cantonese") {
        options("係", cantonese = false) shouldNotContain "喺"
        options("在", cantonese = false).take(2) shouldBe listOf("在", "再")
        Mandarin.syllables.size shouldBe 417
    }
})
