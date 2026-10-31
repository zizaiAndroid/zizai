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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.zizai.han.lexicon.LexiconFormat
import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.reading.Mandarin
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuilderTest : FunSpec({
    fun src(license: String) = Source("x", license, "0", "none", "https://example.invalid/x")
    val syl = Mandarin.syllables::idOf

    fun unihanZip(dir: File): File {
        val zip = File(dir, "Unihan.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Unihan_Readings.txt"))
            z.write(
                """
                # comment
                U+4E8B	kMandarin	shì
                U+4E8B	kHanyuPinlu	shì(6878)
                U+5E02	kMandarin	shì
                U+884C	kMandarin	xíng
                U+884C	kHanyuPinlu	xíng(2943) háng(218)
                U+884C	kHanyuPinyin	20811.060:háng,xìng,xíng,hàng,héng
                U+9EB8	kMandarin	ê
                """.trimIndent().toByteArray(),
            )
            z.putNextEntry(ZipEntry("Unihan_OtherMappings.txt"))
            z.write("U+884C\tkTGH\t2013:483\nU+884C\tkBigFive\tA6E6\nU+884C\tkGB0\t4848\n".toByteArray())
            z.putNextEntry(ZipEntry("Unihan_IRGSources.txt"))
            z.write("U+884C\tkTotalStrokes\t6\n".toByteArray())
        }
        return zip
    }

    test("license gate allows permissive licenses and rejects GPL and share-alike") {
        LicenseGate.check(listOf(src("Apache-2.0"), src("Unicode-3.0"), src("MIT"), src("CC-BY-4.0")))
        shouldThrow<IllegalStateException> { LicenseGate.check(listOf(src("GPL-3.0"))) }.message shouldContain "never"
        shouldThrow<IllegalStateException> { LicenseGate.check(listOf(src("LGPL-3.0"))) }
        shouldThrow<IllegalStateException> { LicenseGate.check(listOf(src("CC-BY-SA-4.0"))) }
        // Share-alike only for a named pack source, and GPL not even there.
        LicenseGate.check(listOf(Source("pinyin-flex", "CC-BY-SA-4.0", "0", "none", "https://example.invalid")))
        shouldThrow<IllegalStateException> {
            LicenseGate.check(listOf(Source("pinyin-flex", "GPL-3.0", "0", "none", "https://example.invalid")))
        }
    }

    test("sources.lock parses and passes the gate") {
        val lock = SourceLock.read(File("sources.lock"))
        lock.map { it.name }.take(2) shouldBe listOf("unihan", "aosp-rawdict")
        lock.map { it.name }.toSet() shouldBe
            (setOf("unihan", "aosp-rawdict", "yue-chars", "yue-words", "yue-essay") + OpenCc.DICTIONARIES.values.flatten() + LicenseGate.PACK_ONLY)
        LicenseGate.check(lock)
    }

    test("Unihan fields are read into readings, tiers and flags") {
        val chars = Unihan.read(unihanZip(tempdir()))
        val xing = chars.getValue(0x884C)
        xing.readings.keys.map { it.toString() } shouldBe listOf("xing2", "hang2", "xing4", "hang4", "heng2")
        xing.readings.values.toList() shouldBe listOf(2943, 218, 0, 0, 0)
        xing.tier shouldBe 1
        xing.big5 shouldBe 0xA6E6
        xing.gb2312 shouldBe true
        xing.strokes shouldBe 6
        (0x9EB8 in chars) shouldBe false // ê only, outside the inventory
    }

    test("merge orders homophones by frequency and the output is byte-identical across runs") {
        val unihan = Unihan.read(unihanZip(tempdir()))
        val aosp = listOf(
            AospEntry("事", 5000.0, intArrayOf(syl("shi"))),
            AospEntry("市", 900.0, intArrayOf(syl("shi"))),
            AospEntry("行", 3000.0, intArrayOf(syl("xing"))),
            AospEntry("行", 800.0, intArrayOf(syl("hang"))),
            AospEntry("银行", 400.0, intArrayOf(syl("yin"), syl("hang"))),
            AospEntry("银", 300.0, intArrayOf(syl("yin"))),
        )
        val lex = CoreLexicon.build(unihan, aosp)
        lex.postings[syl("shi")].map { it.codePoint } shouldBe listOf(0x4E8B, 0x5E02)
        lex.postings[syl("xing")].single().toneMask shouldBe ((1 shl 2) or (1 shl 4))
        lex.chars.first { it.codePoint == 0x884C }.readings.first().packed shouldBe LexiconFormat.packReading(syl("xing"), 2)
        lex.words.single().text shouldBe "银行"
        Invariants.check(lex, expectGeneralStandard = false).filterNot { it.startsWith("syllables without") }.shouldBeEmpty()

        val a = LexiconWriter.write(lex)
        val b = LexiconWriter.write(CoreLexicon.build(Unihan.read(unihanZip(tempdir())), aosp.reversed()))
        sha256(a) shouldBe sha256(b)
        (a.size % 4) shouldBe 0
    }

    // Needs the downloaded sources (run the builder once); skipped where they're absent, as in CI.
    val cache = File("../../build/han-sources")
    test("on the full data, the reader agrees with the builder's own index").config(
        enabled = File(cache, "unihan").exists() && File(cache, "aosp-rawdict").exists(),
    ) {
        val lex = CoreLexicon.build(Unihan.read(File(cache, "unihan")), AospDict.read(File(cache, "aosp-rawdict")))
        val bytes = LexiconWriter.write(lex)
        sha256(bytes) shouldBe sha256(File("../../app/src/main/assets/han/han-core.bin").readBytes())
        val reader = LexiconReader(ByteBuffer.wrap(bytes))
        reader.charCount shouldBe lex.chars.size
        lex.chars.forEachIndexed { i, c ->
            reader.charIndex(c.codePoint) shouldBe i
            reader.readingCount(i) shouldBe c.readings.size
            c.readings.forEachIndexed { j, r -> reader.reading(i, j) shouldBe r.packed }
        }
        lex.postings.forEachIndexed { s, list ->
            reader.postingCount(s) shouldBe list.size
            list.forEachIndexed { j, p -> reader.postingCodePoint(reader.postingStart(s) + j) shouldBe p.codePoint }
        }
        lex.words.forEachIndexed { w, word ->
            reader.wordText(w) shouldBe word.text
            IntArray(reader.wordSyllableCount(w)) { reader.wordSyllable(w, it) }.toList() shouldBe word.syllables.toList()
            val found = ArrayList<Int>()
            reader.forEachWord(word.syllables, word.syllables, word.syllables.size, true) { found += it; true }
            (w in found) shouldBe true
            reader.wordsWithText(word.text).map(reader::wordByText).contains(w) shouldBe true
        }
    }
})
