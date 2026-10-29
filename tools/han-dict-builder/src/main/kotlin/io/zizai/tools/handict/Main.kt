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

import io.zizai.han.HanEngine
import java.io.File
import kotlin.system.exitProcess

/**
 * Builds `han-core.bin` and `manifest.json` (docs/IMPLEMENTATION_PLAN.md P1-04).
 *
 * ```
 * ./gradlew :tools:han-dict-builder:run
 * ./gradlew :tools:han-dict-builder:run --args="--lock FILE --cache DIR --out DIR"
 * ```
 * Paths default to the repository layout, relative to the repository root.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val lockFile = File(opts["--lock"] ?: "tools/han-dict-builder/sources.lock")
    val cache = File(opts["--cache"] ?: "build/han-sources")
    val outDir = File(opts["--out"] ?: "app/src/main/assets/han")
    try {
        build(lockFile, cache, outDir)
    } catch (e: IllegalStateException) {
        System.err.println("han-dict-builder: ${e.message}")
        exitProcess(1)
    }
}

fun build(lockFile: File, cache: File, outDir: File) {
    val sources = SourceLock.read(lockFile)
    LicenseGate.check(sources)
    val byName = sources.associateBy { it.name }
    val fetch = SourceCache(cache)
    fun file(name: String) = fetch.fetch(byName[name] ?: error("sources.lock has no '$name'"))

    val unihan = Unihan.read(file("unihan"))
    val aosp = AospDict.read(file("aosp-rawdict"))
    println("Unihan: ${unihan.size} characters (${Unihan.unparsedReadings} readings outside the inventory)")
    println("AOSP: ${aosp.size} entries (${AospDict.skipped} skipped)")

    val lex = CoreLexicon.build(unihan, aosp)
    val problems = Invariants.check(lex)
    check(problems.isEmpty()) { "invariants failed:\n  " + problems.take(30).joinToString("\n  ") }

    val bytes = LexiconWriter.write(lex)
    outDir.mkdirs()
    File(outDir, "han-core.bin").writeBytes(bytes)
    File(outDir, "manifest.json").writeText(manifest(sources, bytes, lex))
    println("Wrote ${bytes.size} bytes: ${lex.chars.size} characters, ${lex.postings.sumOf { it.size }} postings, ${lex.words.size} words")
}

/** Deterministic: no timestamps, fixed key order. */
fun manifest(sources: List<Source>, bytes: ByteArray, lex: CoreLexicon): String = buildString {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    append("{\n")
    append("  \"format\": ${HanEngine.LEXICON_FORMAT},\n")
    append("  \"files\": {\n    \"han-core.bin\": { \"size\": ${bytes.size}, \"sha256\": ${q(sha256(bytes))} }\n  },\n")
    append("  \"stats\": { \"characters\": ${lex.chars.size}, \"postings\": ${lex.postings.sumOf { it.size }}, \"words\": ${lex.words.size} },\n")
    append("  \"sources\": [\n")
    append(sources.joinToString(",\n") {
        "    { \"name\": ${q(it.name)}, \"license\": ${q(it.license)}, \"sha256\": ${q(it.sha256)}, \"url\": ${q(it.url)} }"
    })
    append("\n  ]\n}\n")
}
