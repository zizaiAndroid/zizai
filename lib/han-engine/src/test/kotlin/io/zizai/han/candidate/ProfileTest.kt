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

import io.kotest.core.spec.style.FunSpec
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.lexicon.coreLexiconFile
import io.zizai.han.parse.PinyinParser
import java.io.File

/** Stage timings on the JVM; runs only with ZZ_PROFILE set and writes build/profile.txt. */
class ProfileTest : FunSpec({
    test("profile").config(enabled = System.getenv("ZZ_PROFILE") != null) {
        val engine = CandidateEngine(LexiconReader.open(coreLexiconFile))
        val inputs = listOf("jintiantianqihenhao", "womenyiqiqubeijing", "zgrmghg", "shishishishishi")
        fun time(label: String, block: () -> Unit): String {
            repeat(20) { block() }
            val t0 = System.nanoTime()
            repeat(50) { block() }
            return "$label %.3f ms".format((System.nanoTime() - t0) / 50 / 1e6)
        }
        val out = StringBuilder()
        for (input in inputs) {
            val l = PinyinParser.parse(input)
            out.appendLine(input)
            out.appendLine("  " + time("parse") { PinyinParser.parse(input) })
            out.appendLine("  " + time("bestPaths") { l.bestPaths(1) })
            out.appendLine("  " + time("sentence") { engine.bestSentence(l) })
            out.appendLine("  " + time("page0+1") { engine.candidates(l).take(18).toList() })
        }
        File("build/profile.txt").writeText(out.toString())
    }
})
