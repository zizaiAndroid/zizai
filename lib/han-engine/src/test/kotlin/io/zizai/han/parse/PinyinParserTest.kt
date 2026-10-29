/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.parse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import io.zizai.han.reading.Mandarin

class PinyinParserTest : FunSpec({
    val table = Mandarin.syllables
    fun best(input: String, k: Int = 5) = PinyinParser.parse(input).bestPaths(k).map { it.spelling(input) }
    fun kinds(input: String) = PinyinParser.parse(input).bestPaths(1).single().edges.map { it.kind }

    test("xian prefers one syllable but keeps xi'an") {
        best("xian").first() shouldBe "xian"
        best("xian") shouldContain "xi'an"
    }

    test("an apostrophe forces the split") {
        best("xi'an").first() shouldBe "xi'an"
        best("xi'an").none { it == "xian" } shouldBe true
    }

    test("fangan has both readings") {
        val paths = best("fangan")
        paths.take(2).toSet() shouldBe setOf("fang'an", "fan'gan")
    }

    test("jianpin: zg is two initials covering zh") {
        best("zg").first() shouldBe "z'g"
        kinds("zg") shouldBe listOf(EdgeKind.INITIAL, EdgeKind.INITIAL)
        val z = PinyinParser.parse("zg").edgesFrom(0).first { it.kind == EdgeKind.INITIAL }
        z.syllables.map(table::spellingOf) shouldContain "zhong"
    }

    test("mixed: zhongg is a syllable plus an initial") {
        best("zhongg").first() shouldBe "zhong'g"
        kinds("zhongg") shouldBe listOf(EdgeKind.FULL, EdgeKind.INITIAL)
    }

    test("v stands for ü, and lue/nue are accepted") {
        best("lv").first() shouldBe "lv"
        best("nve").first() shouldBe "nve"
        kinds("nve") shouldBe listOf(EdgeKind.FULL)
        val lue = PinyinParser.parse("lue").bestPaths(1).single().edges.single()
        table.spellingOf(lue.first) shouldBe "lve"
    }

    test("an incomplete last syllable is a prefix edge") {
        best("zhon").first() shouldBe "zhon"
        kinds("zhon") shouldBe listOf(EdgeKind.PREFIX)
        val edges = PinyinParser.parse("xia").edgesFrom(0).filter { it.end == 3 }
        edges.map { it.kind }.toSet() shouldBe setOf(EdgeKind.FULL, EdgeKind.PREFIX)
    }

    test("letters no syllable explains pass through as raw edges") {
        kinds("iv") shouldBe listOf(EdgeKind.RAW, EdgeKind.RAW)
        best("niv").first() shouldBe "ni'v"
    }

    test("longer sentence") {
        best("woaibeijingtiananmen").first() shouldBe "wo'ai'bei'jing'tian'an'men"
    }

    val syllables = table.spellings().filter { it !in setOf("m", "n", "ng", "hm", "hng") }

    test("property: any syllable sequence with apostrophes parses back to itself") {
        checkAll(500, Arb.list(Arb.element(syllables), 1..6)) { seq ->
            val input = seq.joinToString("'")
            best(input, 1).single() shouldBe input
        }
    }

    test("property: any syllable sequence without apostrophes is a path in the lattice") {
        checkAll(500, Arb.list(Arb.element(table.spellings()), 1..6)) { seq ->
            PinyinParser.parse(seq.joinToString("")).containsPath(seq.map(table::idOf)) shouldBe true
        }
    }
})
