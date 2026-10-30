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

/** Top candidates for ZZ_INPUTS (space-separated) with the core and the modern pack, into build/explore.txt. */
class ExploreTest : FunSpec({
    test("explore").config(enabled = System.getenv("ZZ_INPUTS") != null) {
        val out = StringBuilder()
        for ((name, file) in listOf("core" to coreLexiconFile, "pack" to File("../../build/han-ext/han-ext-modern.bin"))) {
            val engine = CandidateEngine(LexiconReader.open(file))
            out.appendLine("== $name")
            for (input in System.getenv("ZZ_INPUTS").split(' ').filter { it.isNotEmpty() }) {
                val c = engine.candidates(PinyinParser.parse(input)).take(8).toList()
                out.appendLine("$input: " + c.joinToString(" ") { "${it.text}(%.1f)".format(it.score) })
            }
        }
        File("build/explore.txt").writeText(out.toString())
    }
})
