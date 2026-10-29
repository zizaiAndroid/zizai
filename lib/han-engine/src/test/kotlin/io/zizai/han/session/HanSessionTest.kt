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
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile

/** A stand-in for an app's text field that applies [EditorEdit]s like `InputConnection` does. */
class FakeEditor : EditorContext {
    val text = StringBuilder()
    var cursor = 0
    var composingStart = -1
    var composingEnd = -1

    val committed: String get() = if (composingStart < 0) text.toString() else text.removeRange(composingStart, composingEnd).toString()
    val composing: String get() = if (composingStart < 0) "" else text.substring(composingStart, composingEnd)

    override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun cursorPosition(): Int = cursor

    fun apply(result: KeyResult, stroke: KeyStroke) {
        when (result) {
            KeyResult.NotHandled -> appHandles(stroke)
            is KeyResult.Handled -> {
                result.edits.forEach(::apply)
                if (result.passThrough) appHandles(stroke)
            }
        }
    }

    fun apply(edit: EditorEdit) {
        when (edit) {
            is EditorEdit.SetPreedit -> {
                val start = replaceComposing(edit.text)
                composingStart = start
                composingEnd = start + edit.text.length
                cursor = start + edit.cursor
            }
            is EditorEdit.Commit -> {
                val start = replaceComposing(edit.text)
                composingStart = -1
                composingEnd = -1
                cursor = start + edit.text.length
            }
            is EditorEdit.MarkComposing -> {
                composingStart = edit.start
                composingEnd = edit.end
            }
            EditorEdit.FinishComposing -> {
                composingStart = -1
                composingEnd = -1
            }
        }
    }

    private fun replaceComposing(s: String): Int {
        val start = if (composingStart >= 0) composingStart else cursor
        val end = if (composingStart >= 0) composingEnd else cursor
        text.replace(start, end, s)
        return start
    }

    /** What the app does with a key the IME didn't take. */
    private fun appHandles(stroke: KeyStroke) {
        when (stroke.key) {
            Key.CHAR -> {
                text.insert(cursor, String(Character.toChars(stroke.codePoint)))
                cursor += Character.charCount(stroke.codePoint)
            }
            Key.SPACE -> { text.insert(cursor, ' '); cursor++ }
            Key.ENTER -> { text.insert(cursor, '\n'); cursor++ }
            Key.BACKSPACE -> if (cursor > 0) { text.deleteCharAt(cursor - 1); cursor-- }
            else -> Unit
        }
    }
}

class HanSessionTest : FunSpec({
    val lexicon = LexiconReader.open(coreLexiconFile)

    class Rig {
        val editor = FakeEditor()
        val session = DefaultHanSession(lexicon, editor = editor)

        /** Types [keys]; `{NAME}` is a special key: ESC BS C-BS TAB S-TAB DOWN UP LEFT RIGHT F2 S-F2 PGDN. */
        fun type(keys: String): Rig {
            var i = 0
            while (i < keys.length) {
                val stroke = if (keys[i] == '{') {
                    val end = keys.indexOf('}', i)
                    val name = keys.substring(i + 1, end)
                    i = end + 1
                    when (name) {
                        "ESC" -> KeyStroke.of(Key.ESCAPE)
                        "BS" -> KeyStroke.of(Key.BACKSPACE)
                        "C-BS" -> KeyStroke.of(Key.BACKSPACE, ctrl = true)
                        "TAB" -> KeyStroke.of(Key.TAB)
                        "S-TAB" -> KeyStroke.of(Key.TAB, shift = true)
                        "DOWN" -> KeyStroke.of(Key.DOWN)
                        "UP" -> KeyStroke.of(Key.UP)
                        "LEFT" -> KeyStroke.of(Key.LEFT)
                        "RIGHT" -> KeyStroke.of(Key.RIGHT)
                        "F2" -> KeyStroke.of(Key.FLIP)
                        "S-F2" -> KeyStroke.of(Key.FLIP, shift = true)
                        "PGDN" -> KeyStroke.of(Key.PAGE_DOWN)
                        else -> error(name)
                    }
                } else {
                    val c = keys[i++]
                    when (c) {
                        ' ' -> KeyStroke.of(Key.SPACE)
                        '\n' -> KeyStroke.of(Key.ENTER)
                        else -> KeyStroke.char(c, shift = c.isUpperCase())
                    }
                }
                editor.apply(session.onKey(stroke), stroke)
            }
            return this
        }

        val text get() = editor.text.toString()
        val ui get() = session.ui.value
    }

    test("type and select with Space or a digit") {
        Rig().type("nihao ").text shouldBe "你好"
        Rig().type("zhongguo1").text shouldBe "中国"
        Rig().type("women ").type("shi ").type("zhongguoren ").text shouldBe "我们是中国人"
    }

    test("the preedit is spelled Pinyin, composing inline, until commit") {
        val r = Rig().type("nihao")
        r.editor.composing shouldBe "ni'hao"
        r.ui.mode shouldBe SessionMode.COMPOSING
        r.ui.candidates.first().text shouldBe "你好"
        r.type(" ")
        r.editor.composing shouldBe ""
        r.ui shouldBe HanUiState.Idle
    }

    test("partial commit keeps the rest composing") {
        val r = Rig().type("nihaoma")
        val i = r.ui.candidates.indexOfFirst { it.text == "你好" }
        withClue(r.ui.candidates.map { it.text }) { (i >= 0) shouldBe true }
        r.type("${i + 1}")
        r.editor.composing shouldBe "你好ma"
        r.type(" ")
        r.text shouldStartWith "你好"
        r.text.length shouldBe 3
    }

    test("Esc cancels, Enter commits the letters, Backspace edits") {
        Rig().type("nihao{ESC}").text shouldBe ""
        Rig().type("nihao\n").text shouldBe "nihao"
        Rig().type("nihaoo{BS} ").text shouldBe "你好"
        Rig().type("ni{BS}{BS}").let { it.text shouldBe ""; it.ui.mode shouldBe SessionMode.IDLE }
        Rig().type("nihaoma{C-BS}").editor.composing shouldBe "ni'hao"
    }

    test("Backspace after a partial selection restores its Pinyin") {
        val r = Rig().type("nihaoma")
        r.type("${r.ui.candidates.indexOfFirst { it.text == "你好" } + 1}")
        r.type("{BS}{BS}")
        r.editor.composing shouldBe "ni'hao"
    }

    test("full-width punctuation while idle, and after a conversion") {
        Rig().type(",").text shouldBe "，"
        Rig().type("3.").text shouldBe "3."
        Rig().type("\"\"''").text shouldBe "“”‘’"
        Rig().type("nihao,").text shouldBe "你好，"
        Rig().type("nihao?").text shouldBe "你好？"
        Rig().type("[]").text shouldBe "【】"
        Rig().type("A").text shouldBe "A"
    }

    test("Tab cycles the tone filter over characters") {
        val r = Rig().type("shi{TAB}{TAB}{TAB}{TAB}")
        r.ui.toneFilter shouldBe 4
        r.ui.candidates.forEach { (it.toneMask and (1 shl 4)) shouldBe (1 shl 4) }
        r.ui.candidates.map { it.text } shouldContain "是"
        r.type("{S-TAB}").ui.toneFilter shouldBe 3
    }

    test("Down expands to a grid with one row per tone") {
        val r = Rig().type("shi{DOWN}")
        r.ui.mode shouldBe SessionMode.EXPANDED
        r.ui.grid.map { it.label } shouldBe listOf("shī", "shí", "shǐ", "shì", "shi")
        r.type("{DOWN}{DOWN}{DOWN}")
        r.ui.grid[r.ui.gridRow].label shouldBe "shì"
        r.type(" ").text shouldBe r.ui.let { "是" }
    }

    test("以词定字 with [ and ]") {
        Rig().type("yinhang[").text shouldBe "银"
        Rig().type("yinhang]").text shouldBe "行"
    }

    test("paging and highlight") {
        val r = Rig().type("yi")
        val first = r.ui.candidates
        r.type("=").ui.page shouldBe 1
        (r.ui.candidates != first) shouldBe true
        r.type("-").ui.page shouldBe 0
        r.type("{RIGHT}{RIGHT}").ui.highlighted shouldBe 2
        val third = r.ui.candidates[2].text
        r.type(" ").text shouldBe third
    }

    test("flip: F2 reconverts the character before the cursor in place") {
        val r = Rig()
        r.editor.text.append("我在"); r.editor.cursor = 2
        r.type("{F2}")
        r.ui.mode shouldBe SessionMode.RECONVERTING
        r.text shouldBe "我再"
        r.type("{F2}{S-F2}\n")
        r.text shouldBe "我再"
        r.ui.mode shouldBe SessionMode.IDLE

        val esc = Rig()
        esc.editor.text.append("我在"); esc.editor.cursor = 2
        esc.type("{F2}{F2}{ESC}").text shouldBe "我在"

        val typing = Rig()
        typing.editor.text.append("我在"); typing.editor.cursor = 2
        typing.type("{F2}hao ").text shouldBe "我再好"
    }

    test("flip: digits pick and the tone filter applies") {
        val r = Rig()
        r.editor.text.append("事"); r.editor.cursor = 1
        r.type("{F2}")
        val options = r.ui.candidates.map { it.text }
        options.first() shouldBe "事"
        r.type("3").text shouldBe options[2]
    }

    // Timing on shared CI runners is noise; the real budget is measured on the phone (P1-20).
    test("onKey stays under 2 ms at the 95th percentile (JVM)").config(enabled = System.getenv("CI") == null) {
        val times = ArrayList<Long>()
        repeat(30) {
            val r = Rig()
            for (c in "jintiantianqihenhao") {
                val s = KeyStroke.char(c)
                val t0 = System.nanoTime()
                val res = r.session.onKey(s)
                times += System.nanoTime() - t0
                r.editor.apply(res, s)
            }
            r.type(" ")
        }
        val warm = times.drop(times.size / 3).sorted()
        val p95 = warm[(warm.size * 0.95).toInt()] / 1e6
        withClue("p95 = $p95 ms") { p95 shouldBeLessThan 2.0 }
    }
})
