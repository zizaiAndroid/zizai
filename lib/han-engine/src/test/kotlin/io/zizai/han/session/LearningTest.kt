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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.zizai.han.candidate.CandidateEngine
import io.zizai.han.candidate.LearnedDictionary
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile

/** P2-02: repeated choices rise, assembled phrases come back, blocked ones stay away, privacy holds. */
class LearningTest : FunSpec({
    val lexicon = LexiconReader.open(coreLexiconFile)

    class Rig(val dict: LearnedDictionary = LearnedDictionary(), learning: Boolean = true) {
        val editor = FakeEditor()
        val session = DefaultHanSession(
            lexicon, CandidateEngine(lexicon, dict), settings = { HanSettings(learning = learning) }, editor = editor,
        )

        fun keys(text: String): Rig {
            for (c in text) {
                val s = when (c) {
                    ' ' -> KeyStroke.of(Key.SPACE)
                    '!' -> KeyStroke.of(Key.DELETE, ctrl = true)
                    else -> KeyStroke.char(c)
                }
                editor.apply(session.onKey(s), s)
            }
            return this
        }

        fun pick(input: String, text: String): Rig {
            keys(input)
            val i = session.ui.value.candidates.indexOfFirst { it.text == text }
            check(i >= 0) { "$text not on the first page for $input: ${session.ui.value.candidates.map { it.text }}" }
            return keys("${i + 1}")
        }

        val top get() = session.ui.value.candidates.first().text
    }

    test("a character chosen twice comes first for its syllable") {
        val r = Rig()
        r.keys("shi"); r.top shouldNotBe "事"
        r.keys(" ")
        r.pick("shi", "事").pick("shi", "事")
        r.keys("shi").top shouldBe "事"
    }

    test("造词: a phrase assembled from picks comes back whole, also by its initials") {
        val r = Rig()
        r.keys("zhangsanfeng")
        val first = r.session.ui.value.candidates.first { it.text == "张三" }
        r.keys("${r.session.ui.value.candidates.indexOf(first) + 1}")
        r.pick("", "丰")
        r.editor.text.toString() shouldBe "张三丰"
        r.keys("zhangsanfeng").top shouldBe "张三丰"
        r.keys(" ")
        r.keys("zsf").top shouldBe "张三丰"
    }

    test("Ctrl+Delete blocks the highlighted candidate") {
        val r = Rig()
        r.keys("shi")
        val blocked = r.top
        r.keys("!")
        r.session.ui.value.candidates.map { it.text } shouldNotContain blocked
        r.keys(" ")
        r.keys("shi").session.ui.value.candidates.map { it.text } shouldNotContain blocked
    }

    test("nothing is learned where learning is off") {
        val dict = LearnedDictionary()
        val r = Rig(dict, learning = false)
        r.pick("shi", "事").pick("shi", "事").pick("shi", "事")
        dict.size shouldBe 0
    }

    test("the dictionary survives a save and load") {
        val dict = LearnedDictionary { 42L }
        val r = Rig(dict)
        r.keys("zhangsanfeng")
        r.keys("${r.session.ui.value.candidates.indexOfFirst { it.text == "张三" } + 1}")
        r.pick("", "丰")
        r.keys("shi!")
        val saved = dict.serialize()
        val restored = LearnedDictionary().apply { load(saved) }
        restored.serialize() shouldBe saved
        Rig(restored).keys("zsf").top shouldBe "张三丰"
    }
})
