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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile
import io.zizai.han.parse.PinyinParser
import java.io.File

/**
 * P1-08: the modern pack must measurably improve results. Needs the pack built locally
 * (`./gradlew :tools:han-dict-builder:run --args="--ext-out build/han-ext"`); skipped without it, as in CI.
 */
class ModernPackTest : FunSpec({
    val packFile = File("../../build/han-ext/han-ext-modern.bin")

    // Everyday words since the AOSP list was made, and common ones it ranks poorly.
    val modern = """
        weixin 微信 | erweima 二维码 | shoujihao 手机号 | dianzan 点赞 | waimai 外卖 | pengyouquan 朋友圈
        tucao 吐槽 | yanzhi 颜值 | geili 给力 | shipin 视频 | kuaidi 快递 | wanggou 网购 | yonghu 用户
        mima 密码 | xiazai 下载 | ruanjian 软件 | dianshiju 电视剧 | kafei 咖啡 | chaoshi 超市 | shoucang 收藏
        fenxiang 分享 | guanzhu 关注 | pinglun 评论 | zhibo 直播 | youxiang 邮箱 | zhanghao 账号 | denglu 登录
        tuichu 退出 | shezhi 设置 | gengxin 更新 | anzhuang 安装 | xiaoxi 消息 | tongzhi 通知 | qunliao 群聊
        hongbao 红包 | zhuanzhang 转账 | saoma 扫码 | daka 打卡 | jiaban 加班 | zhoumo 周末 | jiayou 加油
    """.trimIndent().split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(' ').let { (a, b) -> a to b } }

    fun hits(engine: CandidateEngine, cases: List<Pair<String, String>>, n: Int) =
        cases.count { (input, word) -> word in engine.candidates(PinyinParser.parse(input)).take(n).map { it.text }.toList() }

    test("the modern pack finds more modern words in the top 3, and keeps the core's results").config(enabled = packFile.exists()) {
        val core = CandidateEngine(LexiconReader.open(coreLexiconFile))
        val ext = CandidateEngine(LexiconReader.open(packFile))
        val coreHits = hits(core, modern, 3)
        val extHits = hits(ext, modern, 3)
        println("modern words in the top 3: core $coreHits, pack $extHits of ${modern.size}")
        withClue("core $coreHits, pack $extHits of ${modern.size}") {
            extHits shouldBeGreaterThan coreHits
            extHits shouldBeGreaterThan modern.size * 9 / 10
        }
        // Everyday inputs whose top candidate the core gets right should mostly stay right.
        val everyday = listOf(
            "nihao" to "你好", "women" to "我们", "zhongguo" to "中国", "xiexie" to "谢谢", "jintian" to "今天",
            "shi" to "是", "de" to "的", "wo" to "我", "zaijian" to "再见", "keyi" to "可以", "zhidao" to "知道",
            "xianzai" to "现在", "shijian" to "时间", "pengyou" to "朋友", "gongzuo" to "工作", "dianhua" to "电话",
        )
        val lost = everyday.filter { (input, word) ->
            ext.candidates(PinyinParser.parse(input)).take(2).none { it.text == word }
        }
        withClue(lost) { lost.size shouldBeLessThanOrEqual 1 }
    }
})
