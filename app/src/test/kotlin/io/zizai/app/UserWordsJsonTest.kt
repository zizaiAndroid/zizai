/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.app

import io.zizai.han.candidate.LearnedDictionary
import io.zizai.han.reading.Mandarin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P2-02: the export restores into another dictionary, and an import merges rather than replaces. */
class UserWordsJsonTest {
    private fun ids(vararg s: String) = s.map { Mandarin.syllables.idOf(it) }.toIntArray()

    @Test
    fun `export then import restores entries and blocks`() {
        val a = LearnedDictionary { 7L }
        a.learn("张三丰", ids("zhang", "san", "feng"), assembled = true)
        a.learn("微信", ids("wei", "xin"), assembled = false)
        a.learn("微信", ids("wei", "xin"), assembled = false)
        a.block("卫星")
        val json = UserWordsJson.encode(a)
        assertTrue("\"pinyin\": \"zhang san feng\"" in json)

        val b = LearnedDictionary()
        UserWordsJson.decodeInto(b, json)
        assertEquals(a.serialize(), b.serialize())
    }

    @Test
    fun `import merges counts and skips unknown Pinyin`() {
        val d = LearnedDictionary { 1L }
        d.learn("微信", ids("wei", "xin"), assembled = false)
        UserWordsJson.decodeInto(
            d,
            """{"format":"zizai-han-user","version":1,"blocked":[],"entries":[
                {"text":"微信","pinyin":"wei xin","count":5,"lastUsed":9,"assembled":false},
                {"text":"乱码","pinyin":"xx yy","count":3,"lastUsed":9,"assembled":true}]}""",
        )
        assertEquals(listOf("微信" to 5), d.entries().map { it.text to it.count })
    }

    @Test
    fun `a file that isn't ours is refused`() {
        assertFailsWith<IllegalArgumentException> {
            UserWordsJson.decodeInto(LearnedDictionary(), """{"format":"other","entries":[],"blocked":[]}""")
        }
    }
}
