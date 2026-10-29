/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.dictate

import io.zizai.zhtext.PunctuationLocale
import io.zizai.zhtext.ZhConverter
import io.zizai.zhtext.ZhScript
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Transcript fixtures of the kind Whisper-style recognizers produce for Mandarin (P1-10). */
class TranscriptPostProcessingTest {
    private val converter = ZhConverter.open(File("src/main/assets/han/zh-convert.bin"))

    private fun process(text: String, script: ZhScript = ZhScript.SIMPLIFIED, locale: PunctuationLocale = PunctuationLocale.MAINLAND) =
        TranscriptPostProcessingSink.process(text, converter, script, locale, cjkLatinSpacing = false)

    @Test
    fun `mixed-script output with spaces and ASCII punctuation comes out as clean Simplified`() {
        assertEquals("我们明天下午开会，记得带电脑。", process("我們 明天 下午 開會, 記得 帶 電腦."))
        assertEquals("这个软件很好用！你试过吗？", process("這個軟件很好用! 你試過嗎?"))
    }

    @Test
    fun `numbers and English inside Chinese are kept`() {
        assertEquals("会议在10:30，大约1,000人参加Google I/O。", process("會議在10:30, 大約1,000人參加Google I/O."))
    }

    @Test
    fun `Traditional settings produce Taiwan and Hong Kong forms`() {
        assertEquals("這個軟體的資訊很有用。", process("这个软件的信息很有用.", ZhScript.TAIWAN_PHRASES, PunctuationLocale.TAIWAN))
        // OpenCC's Hong Kong standard uses 説 for 說.
        assertEquals("他説「你好」", process("他说\"你好\"", ZhScript.HONG_KONG, PunctuationLocale.HONG_KONG))
    }

    @Test
    fun `text without Chinese is untouched`() {
        assertEquals("Hello, world .", process("Hello, world ."))
    }
}
