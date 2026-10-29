/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.reading

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.text.Normalizer

private const val MARKS = "̄́̌̀"

class ReadingTest : FunSpec({
    val table = Mandarin.syllables

    test("inventory has about 410 syllables and no duplicates") {
        table.size shouldBeInRange 405..425
        MandarinInventory.SPELLINGS.groupBy { it }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
    }

    test("every syllable round-trips spelling -> id -> spelling") {
        for (id in 0 until table.size) {
            table.idOf(table.spellingOf(id)) shouldBe id
        }
        table.idOf("shi") shouldNotBe -1
        table.idOf("shii") shouldBe -1
        table.idOf("lü") shouldBe -1 // callers normalize first
    }

    test("every reading round-trips through its packed and numbered forms") {
        for (id in 0 until table.size) for (tone in 0..5) {
            val r = Reading.of(Mandarin, id, tone)
            r.syllable shouldBe id
            r.tone shouldBe tone
            r.phonology shouldBe Mandarin
            Reading.parseMandarin(r.toString()) shouldBe r
        }
    }

    test("alternative spellings normalize") {
        Reading.parseMandarin("lü3").toString() shouldBe "lv3"
        Reading.parseMandarin("nue4").toString() shouldBe "nve4"
        Reading.parseMandarin("LU:E4").toString() shouldBe "lve4"
        Reading.parseMandarin("ma").toString() shouldBe "ma"
        Reading.parseMandarin("shi6") shouldBe null
        Reading.parseMandarin("xyz1") shouldBe null
    }

    test("prefix queries") {
        table.isPrefix("zh") shouldBe true
        table.isPrefix("zhuang") shouldBe true
        table.isPrefix("zhuangg") shouldBe false
        table.idsWithPrefix("xia").map(table::spellingOf) shouldBe listOf("xia", "xian", "xiang", "xiao")
        table.idsWithPrefix("v").toList().shouldBeEmpty()
    }

    test("tone marks: golden cases") {
        val cases = mapOf(
            "shi4" to "shì", "ma1" to "mā", "ma5" to "ma", "lv3" to "lǚ", "nve4" to "nüè", "lve4" to "lüè",
            "gui4" to "guì", "liu2" to "liú", "xue2" to "xué", "er2" to "ér", "zhuang4" to "zhuàng",
            "dou1" to "dōu", "you3" to "yǒu", "yu2" to "yú", "jiong3" to "jiǒng", "kuo4" to "kuò",
            "n2" to "ń", "m2" to "ḿ", "ng3" to "ňg", "ai4" to "ài", "ei2" to "éi",
        )
        for ((numbered, marked) in cases) {
            Pinyin.toneMarked(Reading.parseMandarin(numbered)!!) shouldBe marked
        }
    }

    test("tone-marked spellings parse back to readings") {
        for (id in 0 until table.size) for (tone in 1..5) {
            val r = Reading.of(Mandarin, id, tone)
            Pinyin.parseToneMarked(Pinyin.toneMarked(r)) shouldBe r
        }
        Pinyin.parseToneMarked("ê") shouldBe null
        Pinyin.parseToneMarked("de").toString() shouldBe "de5"
    }

    test("tone marks: every syllable gets exactly one mark on a vowel or syllabic consonant") {
        for (id in 0 until table.size) {
            val spelling = table.spellingOf(id)
            Pinyin.toneMarked(spelling, 0) shouldBe Pinyin.display(spelling)
            Pinyin.toneMarked(spelling, 5) shouldBe Pinyin.display(spelling)
            for (tone in 1..4) {
                val nfd = Normalizer.normalize(Pinyin.toneMarked(spelling, tone), Normalizer.Form.NFD)
                nfd.count { it in MARKS } shouldBe 1
                // Removing the tone mark gives back the display spelling; ü keeps its diaeresis.
                Normalizer.normalize(nfd.filterNot { it in MARKS }, Normalizer.Form.NFC) shouldBe Pinyin.display(spelling)
                val markedOn = nfd[nfd.indexOfFirst { it in MARKS } - 1]
                (markedOn in "aeioumn̈") shouldBe true
            }
        }
    }
})
