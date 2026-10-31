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
    override fun textAfterCursor(n: Int): CharSequence = text.substring(cursor, minOf(text.length, cursor + n))
    override fun cursorPosition(): Int = cursor

    /** A selection from [selectionStart] to the cursor, when set. */
    var selectionStart = -1
    override fun selection(): EditorSelection? =
        if (selectionStart in 0 until cursor) EditorSelection(selectionStart, cursor, text.substring(selectionStart, cursor)) else null

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
                        "C-F2" -> KeyStroke.of(Key.FLIP, ctrl = true)
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

    test("flip: the neighbours rank the options and pick the reading") {
        val r = Rig()
        r.editor.text.append("明天在见"); r.editor.cursor = 3
        r.type("{F2}").text shouldBe "明天再见"

        val bank = Rig()
        bank.editor.text.append("银行"); bank.editor.cursor = 2
        bank.type("{F2}")
        // 行 in 银行 reads háng, so the options after it are hang characters, not xing.
        val options = bank.ui.candidates.map { it.text }
        options.first() shouldBe "行"
        withClue(options) { (options[1] in listOf("航", "杭", "巷", "夯", "沆", "绗")) shouldBe true }
    }

    test("Ctrl+F2 flips the word before the cursor as a unit") {
        val r = Rig()
        r.editor.text.append("明天在见"); r.editor.cursor = 4
        r.type("{C-F2}")
        r.ui.candidates.first().text shouldBe "在见"
        r.text shouldBe "明天再见"
        r.type("\n").ui.mode shouldBe SessionMode.IDLE

        val company = Rig()
        company.editor.text.append("我们公式"); company.editor.cursor = 4
        company.type("{C-F2}")
        company.ui.candidates.take(3).map { it.text } shouldContain "共识" // gōng shì, as 公式

        val single = Rig()
        single.editor.text.append("一个呢"); single.editor.cursor = 3
        single.type("{C-F2}{ESC}").text shouldBe "一个呢"
    }

    test("F2 with a selection flips exactly the selection") {
        val word = Rig()
        word.editor.text.append("明天在见吧"); word.editor.selectionStart = 2; word.editor.cursor = 4
        word.type("{F2}")
        word.text shouldBe "明天再见吧"
        word.ui.candidates.first().text shouldBe "在见"

        val char = Rig()
        char.editor.text.append("银行"); char.editor.selectionStart = 0; char.editor.cursor = 1
        char.type("{F2}")
        char.ui.candidates.first().text shouldBe "银"
        char.text.substring(1) shouldBe "行"

        val long = Rig()
        long.editor.text.append("今天天气很好"); long.editor.selectionStart = 0; long.editor.cursor = 6
        long.type("{F2}")
        long.ui.mode shouldBe SessionMode.IDLE
    }

    test("a flip can lead with preferred options, for characters and words") {
        val r = Rig()
        r.editor.text.append("我在见"); r.editor.cursor = 3
        r.editor.apply(r.session.startReconversion(ReconversionTarget("在", 1, 2, "我", "见", preferred = listOf("再"))), KeyStroke.of(Key.OTHER))
        r.text shouldBe "我再见"
        r.ui.candidates.take(2).map { it.text } shouldBe listOf("在", "再")
        r.type("\n")

        val w = Rig()
        w.editor.text.append("他的书"); w.editor.cursor = 0
        w.editor.apply(w.session.startReconversion(ReconversionTarget("他的", 0, 2, preferred = listOf("她的"))), KeyStroke.of(Key.OTHER))
        w.text shouldBe "她的书"
    }

    test("compact preset: ; and ' pick 2nd and 3rd, , and . page, holding Backspace cancels") {
        val compact = HanSettings(compact = true)
        fun rig() = FakeEditor().let { e -> e to DefaultHanSession(lexicon, settings = { compact }, editor = e) }
        fun type(e: FakeEditor, s: DefaultHanSession, keys: String) = keys.forEach { c ->
            val k = KeyStroke.char(c); e.apply(s.onKey(k), k)
        }
        val (e1, s1) = rig()
        type(e1, s1, "shi")
        val second = s1.ui.value.candidates[1].text
        val third = s1.ui.value.candidates[2].text
        type(e1, s1, ";")
        e1.text.toString() shouldBe second
        type(e1, s1, "shi'")
        e1.text.toString() shouldBe second + third

        val (e2, s2) = rig()
        type(e2, s2, "yi.")
        s2.ui.value.page shouldBe 1
        type(e2, s2, ",")
        s2.ui.value.page shouldBe 0
        val held = KeyStroke(Key.BACKSPACE, repeatCount = 1)
        e2.apply(s2.onKey(held), held)
        s2.ui.value.mode shouldBe SessionMode.IDLE
        e2.text.toString() shouldBe ""
    }

    test("flip: an Extension B character flips whole") {
        val r = Rig()
        r.editor.text.append("我𠀀"); r.editor.cursor = 3
        r.type("{F2}{ESC}").text shouldBe "我𠀀"
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
