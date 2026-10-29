/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.candidate

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile
import io.zizai.han.parse.PinyinParser
import io.zizai.han.reading.Mandarin

class CandidateEngineTest : FunSpec({
    val lexicon = LexiconReader.open(coreLexiconFile)
    val engine = CandidateEngine(lexicon)
    fun top(input: String, n: Int, filter: HomophoneFilter = HomophoneFilter()) =
        engine.candidates(PinyinParser.parse(input), filter).take(n).toList()
    fun texts(input: String, n: Int, filter: HomophoneFilter = HomophoneFilter()) = top(input, n, filter).map { it.text }

    // input, expected candidate, rank it must reach (1 = first). Quanpin, jianpin, mixed, sentences.
    val golden = """
        de 的 1 | shi 是 1 | wo 我 1 | ni 你 1 | ta 他 3 | zai 在 1 | you 有 2 | le 了 3 | bu 不 1 | ren 人 1
        yi 一 2 | ge 个 1 | zhe 这 1 | shang 上 1 | lai 来 1 | dao 到 1 | shuo 说 1 | yao 要 1 | jiu 就 1 | qu 去 1
        hao 好 1 | kan 看 1 | xiang 想 1 | hui 会 1 | neng 能 1 | dui 对 1 | zhi 只 3 | hen 很 1 | ye 也 1 | dou 都 1
        xian 先 2 | xian 现 5 | xia 下 1 | lv 绿 3 | lv 率 3 | nv 女 1 | nve 虐 1 | lve 略 2 | er 二 3 | er 而 3
        women 我们 1 | nimen 你们 1 | tamen 他们 1 | zhongguo 中国 1 | beijing 北京 1 | shanghai 上海 1 | jintian 今天 1
        mingtian 明天 1 | zuotian 昨天 1 | xianzai 现在 1 | shijian 时间 1 | wenti 问题 1 | gongzuo 工作 1 | pengyou 朋友 1
        dianhua 电话 1 | shouji 手机 1 | diannao 电脑 1 | wangluo 网络 1 | xuexi 学习 1 | xuesheng 学生 1 | laoshi 老师 1
        yisheng 医生 1 | yiyuan 医院 1 | fangan 方案 1 | fangan 反感 3 | xiexie 谢谢 1 | duibuqi 对不起 1 | meiguanxi 没关系 1
        zaijian 再见 1 | nihao 你好 1 | henhao 很好 1 | keyi 可以 1 | zhidao 知道 1 | yinwei 因为 1 | suoyi 所以 1
        danshi 但是 1 | ruguo 如果 1 | yijing 已经 1 | haishi 还是 1 | huozhe 或者 1 | yiding 一定 1 | feichang 非常 1
        kaishi 开始 1 | jieshu 结束 1 | xihuan 喜欢 1 | renshi 认识 3 | juede 觉得 1 | xiwang 希望 1 | bangzhu 帮助 1
        jisuanji 计算机 1 | zhongguoren 中国人 1 | dajiahao 大家好 1 | yinhang 银行 1 | xingren 行人 3 | chongqing 重庆 1
        changcheng 长城 1 | zhangda 长大 1 | yinyue 音乐 1 | kuaile 快乐 1 | shengri 生日 1 | xingqi 星期 1 | tianqi 天气 1
        xi'an 西安 1 | xi'an 西岸 2 | ji'e 饥饿 1 | dang'an 档案 1 | fang'an 方案 1
        zg 中国 3 | bj 北京 3 | wm 我们 3 | nh 你好 3 | xx 学习 5 | dh 电话 5 | zgr 中国人 1 | jt 今天 5
        zhongg 中国 1 | beij 北京 1 | shangh 上海 1 | womenyiqiqu 我们一起去 1 | woaini 我爱你 1
        jintiantianqihenhao 今天天气很好 1 | wobuzhidao 我不知道 1 | xiexienimen 谢谢你们 1 | woaibeijing 我爱北京 1
        womenshizhongguoren 我们是中国人 1 | nihaoma 你好吗 1 | shishi 实施 2 | shishi 事实 3 | zhon 中 1 | zho 中 3
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map {
        val (input, expected, rank) = it.split(' ')
        Triple(input, expected, rank.toInt())
    }

    test("golden: hand-picked inputs reach their expected rank (${golden.size} cases)") {
        val failures = golden.filter { (input, expected, rank) -> expected !in texts(input, rank) }
            .map { (input, expected, rank) -> "$input → $expected within $rank, got ${texts(input, 8)}" }
        withClue(failures.joinToString("\n")) { failures.size shouldBe 0 }
    }

    test("golden: the 150 most frequent two-character words are in the top 5 for their quanpin") {
        data class W(val text: String, val pinyin: String, val score: Int)
        val words = (0 until lexicon.wordCount).asSequence()
            .filter { lexicon.wordSyllableCount(it) == 2 }
            .map { w -> W(lexicon.wordText(w), (0 until 2).joinToString("") { Mandarin.syllables.spellingOf(lexicon.wordSyllable(w, it)) }, lexicon.wordScore(w)) }
            .sortedByDescending { it.score }
            .distinctBy { it.text }
            .take(150).toList()
        val misses = words.filter { it.text !in texts(it.pinyin, 5) }
        withClue(misses.joinToString { "${it.pinyin}→${it.text}: ${texts(it.pinyin, 5)}" }) {
            misses.size shouldBeLessThanOrEqual 3 // homographs with a much stronger twin may lose
        }
    }

    test("paging through every homophone of yi never materializes the whole list") {
        val total = lexicon.postingCount(Mandarin.syllables.idOf("yi"))
        total shouldBeGreaterThan 300
        val pager = CandidatePager(engine.candidates(PinyinParser.parse("yi"), HomophoneFilter(script = ScriptFilter.BOTH)), 9)
        pager.page(0).size shouldBe 9
        pager.page(1).size shouldBe 9
        pager.pulledCount shouldBeLessThanOrEqual 18
        var page = 2
        while (pager.hasPage(page)) {
            pager.pulledCount shouldBeLessThanOrEqual (page + 1) * 9
            page++
        }
        pager.pulledCount shouldBeGreaterThan total - 1 // every character, plus any yi words
    }

    test("tone, tier and script filters") {
        val fourth = texts("shi", 30, HomophoneFilter(tone = 4))
        fourth shouldContain "是"
        fourth shouldContain "事"
        fourth shouldNotContain "时"
        val common = top("yi", 2000, HomophoneFilter(tier = CharsetTier.COMMON)).filter { it.kind == CandidateKind.CHAR }
        common.size shouldBeLessThanOrEqual 120
        common.size shouldBeGreaterThan 30
        texts("men", 20, HomophoneFilter(script = ScriptFilter.TRADITIONAL)) shouldContain "們"
        texts("men", 200, HomophoneFilter(script = ScriptFilter.SIMPLIFIED)) shouldNotContain "們"
        texts("men", 20, HomophoneFilter(renderable = { it != '们'.code })) shouldNotContain "们"
    }

    test("partial commit: prefix words and first-syllable characters consume part of the input") {
        val c = top("nihaoma", 30)
        c.first().text shouldBe "你好吗"
        val nihao = c.first { it.text == "你好" }
        nihao.end shouldBe 5
        c.first { it.text == "你" }.end shouldBe 2
    }

    test("以词定字 picks a word's first or last character and consumes the whole word") {
        val w = top("yinhang", 1).single()
        w.firstCharacter().text shouldBe "银"
        w.lastCharacter().text shouldBe "行"
        w.lastCharacter().end shouldBe w.end
    }

    test("no duplicates, and raw letters come last when nothing explains them") {
        val c = top("zhongguo", 200).map { it.text }
        c.size shouldBe c.toSet().size
        val raw = top("iv", 50)
        raw.last().kind shouldBe CandidateKind.RAW
        raw.last().text shouldBe "iv"
    }

    test("candidate scores are finite and the first page is fast") {
        top("woaibeijingtiananmen", 9).forEach { it.score.isFinite() shouldBe true }
        if (System.getenv("CI") != null) return@test // timing on shared runners is noise
        val t0 = System.nanoTime()
        repeat(50) { top("jintiantianqihenhao", 9) }
        val perCallMs = (System.nanoTime() - t0) / 50 / 1e6
        withClue("$perCallMs ms") { (20.0 - perCallMs) shouldBeGreaterThan 0.0 }
    }
})
