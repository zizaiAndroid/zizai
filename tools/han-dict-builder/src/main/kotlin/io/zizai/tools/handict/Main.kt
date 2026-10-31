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
 * Builds `han-core.bin`, `zh-convert.bin` and `manifest.json` (docs/IMPLEMENTATION_PLAN.md P1-04, P1-09),
 * and with `--ext-out DIR` also the downloadable `han-ext-modern.bin` pack (P1-08).
 *
 * ```
 * ./gradlew :tools:han-dict-builder:run
 * ./gradlew :tools:han-dict-builder:run --args="--ext-out build/han-ext"
 * ./gradlew :tools:han-dict-builder:run --args="--strings app/src/main/res"   (Traditional UI drafts, P2-09)
 * ./gradlew :tools:han-dict-builder:run --args="--lock FILE --cache DIR --out DIR"
 * ```
 * Paths default to the repository layout, relative to the repository root.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val lockFile = File(opts["--lock"] ?: "tools/han-dict-builder/sources.lock")
    val cache = File(opts["--cache"] ?: "build/han-sources")
    val outDir = File(opts["--out"] ?: "app/src/main/assets/han")
    val extOut = opts["--ext-out"]?.let(::File)
    opts["--strings"]?.let { res ->
        TraditionalStrings.convert(File(res), TraditionalStrings.converter(File(outDir, "zh-convert.bin")))
        return
    }
    try {
        build(lockFile, cache, outDir, extOut)
    } catch (e: IllegalStateException) {
        System.err.println("han-dict-builder: ${e.message}")
        exitProcess(1)
    }
}

fun build(lockFile: File, cache: File, outDir: File, extOut: File? = null) {
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
    val convert = OpenCc.write(OpenCc.DICTIONARIES.mapValues { (_, names) -> OpenCc.read(names.map(::file)) })
    val rime = RimeCantonese.read(file("yue-chars"), file("yue-words"), file("yue-essay"))
    val yueLex = CantoneseLexicon.build(unihan, lex, rime)
    val yue = LexiconWriter.write(yueLex, io.zizai.han.reading.Cantonese)
    outDir.mkdirs()
    File(outDir, "han-core.bin").writeBytes(bytes)
    File(outDir, "zh-convert.bin").writeBytes(convert)
    File(outDir, "han-yue.bin").writeBytes(yue)
    val coreSources = sources.filter { it.name !in LicenseGate.PACK_ONLY }
    File(outDir, "manifest.json").writeText(
        manifest(coreSources, mapOf("han-core.bin" to bytes, "zh-convert.bin" to convert, "han-yue.bin" to yue), lex),
    )
    println("Wrote han-core.bin, ${bytes.size} bytes: ${lex.chars.size} characters, ${lex.postings.sumOf { it.size }} postings, ${lex.words.size} words")
    println("Wrote zh-convert.bin, ${convert.size} bytes")
    println("Wrote han-yue.bin, ${yue.size} bytes: ${yueLex.chars.size} characters, ${yueLex.postings.sumOf { it.size }} postings, ${yueLex.words.size} words")

    if (extOut != null) buildModernPack(unihan, aosp, file("pinyin-flex"), sources, extOut)
}

/**
 * The modern pack is a complete lexicon: the core data merged with upstream's CC BY-SA Pinyin pack. It
 * replaces han-core.bin while installed, so the engine never merges lexicons, and deleting it falls back
 * to the bundled core. Being a derivative of CC-CEDICT it is CC BY-SA 4.0, and ships with its attribution.
 */
fun buildModernPack(unihan: java.util.TreeMap<Int, UnihanChar>, aosp: List<AospEntry>, flex: File, sources: List<Source>, out: File) {
    val rows = ModernPack.read(flex)
    val entries = ModernPack.toEntries(rows, aosp)
    println("Pinyin pack: ${rows.size} rows, ${entries.size} merged (${ModernPack.unsegmented} codes not one syllable per character)")
    val lex = CoreLexicon.build(unihan, aosp + entries)
    val problems = Invariants.check(lex)
    check(problems.isEmpty()) { "modern pack invariants failed:\n  " + problems.take(30).joinToString("\n  ") }
    val bytes = LexiconWriter.write(lex)
    out.mkdirs()
    File(out, "han-ext-modern.bin").writeBytes(bytes)
    File(out, "han-ext-modern.json").writeText(manifest(sources, mapOf("han-ext-modern.bin" to bytes), lex))
    File(out, "ATTRIBUTION.md").writeText(MODERN_ATTRIBUTION)
    println("Wrote han-ext-modern.bin, ${bytes.size} bytes: ${lex.words.size} words, sha256 ${sha256(bytes)}")
}

val MODERN_ATTRIBUTION = """
    # han-ext-modern.bin: sources and attribution

    This file is a lexicon for 字在 Zizai's Chinese input, built by tools/han-dict-builder from the sources
    pinned in tools/han-dict-builder/sources.lock. It is licensed **CC BY-SA 4.0** because it is a
    derivative of CC-CEDICT.

    - **CC-CEDICT** (https://www.mdbg.net/chinese/dictionary?page=cc-cedict), published by MDBG, CC BY-SA 4.0.
      Referenced work: CEDICT, © 1997, 1998 Paul Andrew Denisowski. Word readings.
    - **OPUS OpenSubtitles** zh_cn frequency list (https://opus.nlpl.eu). P. Lison and J. Tiedemann,
      "OpenSubtitles2016", LREC 2016. Aggregate word counts only.
      Both as combined in DictateKeyboard's Chinese Pinyin pack (tools/pinyin-dict/generate.py).
    - **Unihan** (Unicode 18.0.0), © Unicode, Inc., Unicode License v3. Character readings and tiers.
    - **AOSP PinyinIME** rawdict_utf16_65105_freq.txt, © The Android Open Source Project, Apache-2.0.
""".trimIndent() + "\n"

/** Deterministic: no timestamps, fixed key order. */
fun manifest(sources: List<Source>, files: Map<String, ByteArray>, lex: CoreLexicon): String = buildString {
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    append("{\n")
    append("  \"format\": ${HanEngine.LEXICON_FORMAT},\n")
    append("  \"files\": {\n")
    append(files.entries.joinToString(",\n") { (name, b) -> "    ${q(name)}: { \"size\": ${b.size}, \"sha256\": ${q(sha256(b))} }" })
    append("\n  },\n")
    append("  \"stats\": { \"characters\": ${lex.chars.size}, \"postings\": ${lex.postings.sumOf { it.size }}, \"words\": ${lex.words.size} },\n")
    append("  \"sources\": [\n")
    append(sources.joinToString(",\n") {
        "    { \"name\": ${q(it.name)}, \"license\": ${q(it.license)}, \"sha256\": ${q(it.sha256)}, \"url\": ${q(it.url)} }"
    })
    append("\n  ]\n}\n")
}
