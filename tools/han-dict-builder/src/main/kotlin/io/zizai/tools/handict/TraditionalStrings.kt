/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.tools.handict

import io.zizai.zhtext.ZhConverter
import io.zizai.zhtext.ZhScript
import java.io.File
import java.nio.ByteBuffer

/**
 * Drafts the Taiwan and Hong Kong UI strings from the Simplified ones (docs/IMPLEMENTATION_PLAN.md P2-09):
 * OpenCC's s2twp (Taiwan characters and vocabulary) and s2hk. Only Han characters change, so names,
 * placeholders and markup come through untouched. A machine draft for review, and marked as one.
 *
 * ```
 * ./gradlew :tools:han-dict-builder:run --args="--strings app/src/main/res"
 * ```
 */
object TraditionalStrings {
    private val TARGETS = listOf("values-zh-rTW" to ZhScript.TAIWAN_PHRASES, "values-zh-rHK" to ZhScript.HONG_KONG)

    fun convert(resDir: File, converter: ZhConverter) {
        val source = File(resDir, "values-zh-rCN")
        val files = source.listFiles { f -> f.extension == "xml" }?.sortedBy { it.name }.orEmpty()
        check(files.isNotEmpty()) { "no strings in $source" }
        for ((dir, script) in TARGETS) {
            val out = File(resDir, dir).apply { mkdirs() }
            for (f in files) {
                val converted = converter.convert(f.readText(), script)
                val note = "<!-- Machine draft from ${source.name}/${f.name} by OpenCC ${script.name.lowercase()} " +
                    "(tools/han-dict-builder, strings mode); review before release. -->\n"
                val body = if (converted.startsWith("<?xml")) {
                    val end = converted.indexOf("?>") + 2
                    converted.substring(0, end) + "\n" + note + converted.substring(end).trimStart('\n')
                } else note + converted
                File(out, f.name).writeText(body)
            }
            println("Wrote ${files.size} files to $out")
        }
    }

    fun converter(zhConvert: File) = ZhConverter.open(ByteBuffer.wrap(zhConvert.readBytes()))
}
