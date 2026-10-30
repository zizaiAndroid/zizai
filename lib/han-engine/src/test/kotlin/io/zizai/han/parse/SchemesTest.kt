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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.zizai.han.candidate.CandidateEngine
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile
import io.zizai.han.reading.Mandarin

/** P2-03: golden cases per double-Pinyin scheme, and the fuzzy pairs. */
class SchemesTest : FunSpec({
    val engine = CandidateEngine(LexiconReader.open(coreLexiconFile))
    fun top(l: SyllableLattice, n: Int) = engine.candidates(l).take(n).map { it.text }.toList()
    fun spell(keys: String, scheme: PinyinScheme) = DoublePinyin.parse(keys, scheme).bestPaths(1).single().edges
        .joinToString(" ") { if (it.kind == EdgeKind.FULL) Mandarin.syllables.spellingOf(it.first) else "?" }

    // Same everyday words in each scheme: keys, the syllables they spell, a word in the top two (旅行 and
    // 履行 share lǚ xíng).
    val golden = mapOf(
        PinyinScheme.XIAOHE to listOf(
            Triple("vsgo", "zhong guo", "中国"), Triple("nihc", "ni hao", "你好"), Triple("womf", "wo men", "我们"),
            Triple("jbtm", "jin tian", "今天"), Triple("dmhx", "dian hua", "电话"), Triple("bwjk", "bei jing", "北京"),
            Triple("uiji", "shi ji", "实际"), Triple("xtxi", "xue xi", "学习"), Triple("lvxk", "lv xing", "旅行"),
        ),
        PinyinScheme.ZIRANMA to listOf(
            Triple("vsgo", "zhong guo", "中国"), Triple("nihk", "ni hao", "你好"), Triple("womf", "wo men", "我们"),
            Triple("jntm", "jin tian", "今天"), Triple("dmhw", "dian hua", "电话"), Triple("bzjy", "bei jing", "北京"),
            Triple("uiji", "shi ji", "实际"), Triple("xtxi", "xue xi", "学习"), Triple("lvxy", "lv xing", "旅行"),
        ),
        PinyinScheme.MICROSOFT to listOf(
            Triple("vsgo", "zhong guo", "中国"), Triple("nihk", "ni hao", "你好"), Triple("womf", "wo men", "我们"),
            Triple("jntm", "jin tian", "今天"), Triple("dmhw", "dian hua", "电话"), Triple("bzj;", "bei jing", "北京"),
            Triple("uiji", "shi ji", "实际"), Triple("xtxi", "xue xi", "学习"), Triple("lyx;", "lv xing", "旅行"),
        ),
    )

    for ((scheme, cases) in golden) {
        test("$scheme: key pairs spell the right syllables and the word comes up") {
            for ((keys, syllables, word) in cases) {
                withClue("$scheme $keys") {
                    spell(keys, scheme) shouldBe syllables
                    top(DoublePinyin.parse(keys, scheme), 2) shouldContain word
                }
            }
        }
    }

    test("zero-initial syllables") {
        spell("aa", PinyinScheme.XIAOHE) shouldBe "a"
        spell("ah", PinyinScheme.XIAOHE) shouldBe "ang"
        spell("eg", PinyinScheme.XIAOHE) shouldBe "eng"
        spell("ai", PinyinScheme.ZIRANMA) shouldBe "ai"
        spell("er", PinyinScheme.ZIRANMA) shouldBe "er"
        spell("ol", PinyinScheme.MICROSOFT) shouldBe "ai"
        spell("or", PinyinScheme.MICROSOFT).split(' ').first() shouldBe "er"
        spell("oo", PinyinScheme.MICROSOFT) shouldBe "o"
    }

    test("ü after j, q, x is written u; after n, l it stays") {
        spell("jv", PinyinScheme.XIAOHE) shouldBe "ju"
        spell("nv", PinyinScheme.XIAOHE) shouldBe "nv"
        spell("qr", PinyinScheme.ZIRANMA) shouldBe "quan"
        spell("xt", PinyinScheme.MICROSOFT) shouldBe "xue"
    }

    test("a lone last key is an initial waiting for its final") {
        top(DoublePinyin.parse("vsg", PinyinScheme.XIAOHE), 5) shouldContain "中国"
    }

    test("fuzzy pairs add the merged sounds, and only when on") {
        top(PinyinParser.parse("zi"), 40) shouldNotContain "知"
        top(PinyinParser.parse("zi", fuzzy = setOf(Fuzzy.Z_ZH)), 40) shouldContain "知"
        top(PinyinParser.parse("fan", fuzzy = setOf(Fuzzy.AN_ANG)), 40) shouldContain "放"
        top(PinyinParser.parse("nan", fuzzy = setOf(Fuzzy.N_L)), 40) shouldContain "蓝"
        top(PinyinParser.parse("lianxi", fuzzy = setOf(Fuzzy.IAN_IANG)), 3) shouldContain "联系"
        // The syllable as typed still wins.
        top(PinyinParser.parse("zhongguo", fuzzy = Fuzzy.entries.toSet()), 1).single() shouldBe "中国"
        fuzzyVariants("zhang", setOf(Fuzzy.Z_ZH, Fuzzy.AN_ANG)).toSet() shouldBe setOf("zang", "zhan", "zan")
    }
})
