/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.lexicon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.zizai.han.reading.Mandarin
import java.io.File
import kotlin.random.Random

/** Runs against the committed asset, so it checks the real data. */
val coreLexiconFile = File("../../app/src/main/assets/han/han-core.bin")

class LexiconReaderTest : FunSpec({
    val lex = LexiconReader.open(coreLexiconFile)
    val syl = Mandarin.syllables::idOf

    fun homophones(spelling: String, n: Int): String {
        val s = syl(spelling)
        val start = lex.postingStart(s)
        return (start until start + minOf(n, lex.postingCount(s)))
            .joinToString("") { Character.toString(lex.postingCodePoint(it)) }
    }

    fun words(vararg ranges: IntRange, exact: Boolean = true): List<String> {
        val out = ArrayList<String>()
        lex.forEachWord(ranges.map { it.first }.toIntArray(), ranges.map { it.last }.toIntArray(), ranges.size, exact) {
            out += lex.wordText(it)
            true
        }
        return out
    }
    fun one(spelling: String) = syl(spelling).let { it..it }

    test("homophone lists start with the common characters") {
        homophones("shi", 6).toList().map(Char::toString) shouldContainAll listOf("是", "时", "事")
        homophones("de", 1) shouldBe "的"
        lex.postingCount(syl("yi")) shouldBeGreaterThan 300
    }

    test("character records carry readings, tiers and flags") {
        val xing = lex.charIndex('行'.code)
        val readings = (0 until lex.readingCount(xing)).map {
            val r = lex.reading(xing, it)
            Mandarin.syllables.spellingOf(LexiconReader.readingSyllable(r)) + LexiconReader.readingTone(r)
        }
        readings.first() shouldBe "xing2"
        readings shouldContain "hang2"
        lex.tier(xing) shouldBe 1
        (lex.flags(xing) and LexiconFormat.FLAG_GB2312) shouldBe LexiconFormat.FLAG_GB2312
        lex.charIndex('a'.code) shouldBe -1
        (lex.flags(lex.charIndex('們'.code)) and LexiconFormat.FLAG_TRADITIONAL) shouldBe LexiconFormat.FLAG_TRADITIONAL
        (lex.flags(lex.charIndex('们'.code)) and LexiconFormat.FLAG_SIMPLIFIED) shouldBe LexiconFormat.FLAG_SIMPLIFIED
    }

    test("words by full syllables, by initials, and as completions") {
        words(one("yin"), one("hang")) shouldContain "银行"
        words(one("zhong"), one("guo")).first() shouldBe "中国"
        val z = Mandarin.syllables.idsWithPrefix("z")
        val g = Mandarin.syllables.idsWithPrefix("g")
        words(z, g) shouldContain "中国"
        words(one("zhong"), g) shouldContain "中国"
        words(one("bei"), one("jing"), exact = false) shouldContainAll listOf("北京", "北京市")
        words(one("bei"), one("jing"), exact = true).contains("北京市") shouldBe false
    }

    test("words by text, including polyphones") {
        val range = lex.wordsWithText("银行")
        range.count() shouldBeGreaterThan 0
        val w = lex.wordByText(range.first)
        lex.wordText(w) shouldBe "银行"
        (0 until lex.wordSyllableCount(w)).map { Mandarin.syllables.spellingOf(lex.wordSyllable(w, it)) } shouldBe
            listOf("yin", "hang")
        lex.wordsWithText("不存在的词语").isEmpty() shouldBe true
    }

    test("every General Standard character is reachable from each of its readings") {
        var standard = 0
        for (c in 0 until lex.charCount) {
            if (lex.tier(c) == 0) continue
            standard++
            val cp = lex.charCodePoint(c)
            for (i in 0 until lex.readingCount(c)) {
                val s = LexiconReader.readingSyllable(lex.reading(c, i))
                val start = lex.postingStart(s)
                (start until start + lex.postingCount(s)).any { lex.postingCodePoint(it) == cp } shouldBe true
            }
        }
        standard shouldBe 8105
    }

    test("random lookups stay well inside the keystroke budget").config(enabled = System.getenv("CI") == null) {
        val rnd = Random(42)
        val firsts = IntArray(2)
        val lasts = IntArray(2)
        var sink = 0L
        fun round() {
            repeat(10_000) {
                val a = rnd.nextInt(Mandarin.syllables.size)
                val b = rnd.nextInt(Mandarin.syllables.size)
                firsts[0] = a; lasts[0] = a; firsts[1] = b; lasts[1] = b
                lex.forEachWord(firsts, lasts, 2, true) { sink += it; true }
                sink += lex.postingCount(a) + lex.charIndex(0x4E00 + rnd.nextInt(20_000))
            }
        }
        round() // warm up
        val t0 = System.nanoTime()
        round()
        val perLookupMicros = (System.nanoTime() - t0) / 10_000 / 1_000
        perLookupMicros shouldBeLessThan 200L
    }
})
