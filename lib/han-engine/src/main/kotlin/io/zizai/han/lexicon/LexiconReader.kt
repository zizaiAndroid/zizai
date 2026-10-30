/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.lexicon

import io.zizai.han.HanEngine
import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Phonology
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Receives word indices from [LexiconReader.forEachWord] without allocating. Return false to stop. */
fun interface WordVisitor {
    fun visit(word: Int): Boolean
}

/**
 * Reads `han-core.bin` (layout on [LexiconFormat]) straight from a buffer, usually memory-mapped. Lookups
 * do absolute reads only, so they don't allocate and one reader can be shared by threads.
 *
 * Indices returned here (character, posting, word) are positions in their sections, valid for this file.
 */
class LexiconReader(buffer: ByteBuffer, val phonology: Phonology = Mandarin) {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)

    private val charBase: Int
    private val readBase: Int
    private val pidxBase: Int
    private val postBase: Int
    private val wordBase: Int
    private val wsylBase: Int
    private val wtxtBase: Int
    private val strsBase: Int

    val charCount: Int
    val postingTotal: Int
    val wordCount: Int

    init {
        check(buf.getInt(0) == LexiconFormat.MAGIC) { "not a han lexicon" }
        val version = buf.getInt(4)
        check(version == HanEngine.LEXICON_FORMAT) { "lexicon format $version, engine reads ${HanEngine.LEXICON_FORMAT}" }
        val sections = HashMap<Int, Int>()
        for (i in 0 until buf.getInt(8)) {
            val at = LexiconFormat.HEADER_SIZE + i * LexiconFormat.SECTION_ENTRY_SIZE
            sections[buf.getInt(at)] = buf.getInt(at + 4)
        }
        fun section(tag: Int) = sections[tag] ?: error("missing section ${tagName(tag)}")
        checkSyllables(section(LexiconFormat.SYLL))
        charBase = section(LexiconFormat.CHAR) + 4
        readBase = section(LexiconFormat.READ) + 4
        pidxBase = section(LexiconFormat.PIDX) + 4
        postBase = section(LexiconFormat.POST) + 4
        wordBase = section(LexiconFormat.WORD) + 4
        wsylBase = section(LexiconFormat.WSYL) + 4
        wtxtBase = section(LexiconFormat.WTXT) + 4
        strsBase = section(LexiconFormat.STRS)
        charCount = buf.getInt(charBase - 4)
        postingTotal = buf.getInt(postBase - 4)
        wordCount = buf.getInt(wordBase - 4)
    }

    // ---- Characters ----

    /** Index of [codePoint] in the character table, or -1. */
    fun charIndex(codePoint: Int): Int {
        var lo = 0
        var hi = charCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cp = buf.getInt(charBase + mid * LexiconFormat.CHAR_RECORD)
            when {
                cp < codePoint -> lo = mid + 1
                cp > codePoint -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun charCodePoint(char: Int): Int = buf.getInt(charRecord(char))
    fun readingCount(char: Int): Int = u8(charRecord(char) + 8)
    fun tier(char: Int): Int = u8(charRecord(char) + 9)
    fun strokes(char: Int): Int = u8(charRecord(char) + 10)
    fun flags(char: Int): Int = u8(charRecord(char) + 11)

    /** The [i]th reading of [char], best first: syllable in [readingSyllable], tone in [readingTone]. */
    fun reading(char: Int, i: Int): Int = u16(readRecord(char, i))
    fun readingWeight(char: Int, i: Int): Int = u16(readRecord(char, i) + 2)

    // ---- Postings: every character for a toneless syllable, best first ----

    fun postingStart(syllable: Int): Int = buf.getInt(pidxBase + syllable * LexiconFormat.PIDX_RECORD)
    fun postingCount(syllable: Int): Int = buf.getInt(pidxBase + syllable * LexiconFormat.PIDX_RECORD + 4)

    fun postingCodePoint(posting: Int): Int = buf.getInt(postRecord(posting))
    fun postingToneMask(posting: Int): Int = u8(postRecord(posting) + 4)
    fun postingTier(posting: Int): Int = u8(postRecord(posting) + 5)
    fun postingFlags(posting: Int): Int = u8(postRecord(posting) + 6)
    fun postingScore(posting: Int): Int = u8(postRecord(posting) + 7)

    // ---- Words ----

    fun wordSyllableCount(word: Int): Int = u8(wordRecord(word) + 4)
    fun wordScore(word: Int): Int = u8(wordRecord(word) + 5)
    fun wordSyllable(word: Int, i: Int): Int = u16(wsylBase + (buf.getInt(wordRecord(word)) + i) * 2)

    /** The word's text. Allocates; call it for words you show, not while searching. */
    fun wordText(word: Int): String {
        val r = wordRecord(word)
        val bytes = ByteArray(buf.getInt(r + 12))
        val src = buf.duplicate()
        src.position(strsBase + buf.getInt(r + 8))
        src.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Visits every word whose syllable `i` lies in `firsts[i]..lasts[i]` for `i < count`, in lexicon order
     * (by syllable sequence, then score). With [exactLength] only words of exactly [count] syllables are
     * visited; otherwise longer words too (completions).
     */
    fun forEachWord(firsts: IntArray, lasts: IntArray, count: Int, exactLength: Boolean, visitor: WordVisitor) {
        if (count == 0) return
        search(0, wordCount, 0, firsts, lasts, count, exactLength, visitor)
    }

    /**
     * Words whose text is exactly [text], as a range of positions for [wordByText]. Allocates the UTF-8
     * form of [text] once.
     */
    fun wordsWithText(text: String): IntRange {
        val key = text.toByteArray(Charsets.UTF_8)
        val from = textBound(key, upper = false)
        val to = textBound(key, upper = true)
        return from until to
    }

    fun wordByText(position: Int): Int = buf.getInt(wtxtBase + position * 4)

    // ---- Internals ----

    private fun search(
        lo: Int, hi: Int, depth: Int, firsts: IntArray, lasts: IntArray, count: Int, exact: Boolean,
        visitor: WordVisitor,
    ): Boolean {
        // Within [lo, hi) every word matches positions < depth with the same syllables, so words of exactly
        // `depth` syllables come first, and the rest are sorted by their syllable at `depth`.
        var i = lo
        if (depth == count) {
            while (i < hi) {
                if (exact && wordSyllableCount(i) != count) break
                if (!visitor.visit(i)) return false
                i++
            }
            return true
        }
        while (i < hi && wordSyllableCount(i) == depth) i++
        val first = firsts[depth]
        val last = lasts[depth]
        i = lowerBound(i, hi, depth, first)
        while (i < hi) {
            val v = wordSyllable(i, depth)
            if (v > last) break
            val j = lowerBound(i, hi, depth, v + 1)
            if (!search(i, j, depth + 1, firsts, lasts, count, exact, visitor)) return false
            i = j
        }
        return true
    }

    /** First word in [lo, hi) whose syllable at [depth] is ≥ [value]. All words there are longer than depth. */
    private fun lowerBound(lo: Int, hi: Int, depth: Int, value: Int): Int {
        var l = lo
        var h = hi
        while (l < h) {
            val mid = (l + h) ushr 1
            if (wordSyllable(mid, depth) < value) l = mid + 1 else h = mid
        }
        return l
    }

    private fun textBound(key: ByteArray, upper: Boolean): Int {
        var l = 0
        var h = wordCount
        while (l < h) {
            val mid = (l + h) ushr 1
            val c = compareText(wordByText(mid), key)
            if (c < 0 || (upper && c == 0)) l = mid + 1 else h = mid
        }
        return l
    }

    private fun compareText(word: Int, key: ByteArray): Int {
        val r = wordRecord(word)
        val off = strsBase + buf.getInt(r + 8)
        val len = buf.getInt(r + 12)
        for (i in 0 until minOf(len, key.size)) {
            val c = (buf.get(off + i).toInt() and 0xFF) - (key[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return len - key.size
    }

    private fun checkSyllables(base: Int) {
        val table = phonology.syllables
        check(buf.getInt(base) == table.size) { "lexicon has ${buf.getInt(base)} syllables, engine has ${table.size}" }
        var at = base + 4
        for (id in 0 until table.size) {
            val len = u8(at)
            val s = String(CharArray(len) { buf.get(at + 1 + it).toInt().toChar() })
            check(s == table.spellingOf(id)) { "syllable $id is '$s' in the lexicon, '${table.spellingOf(id)}' in the engine" }
            at += 1 + len
        }
    }

    private fun charRecord(char: Int) = charBase + char * LexiconFormat.CHAR_RECORD
    private fun readRecord(char: Int, i: Int) =
        readBase + (buf.getInt(charRecord(char) + 4) + i) * LexiconFormat.READ_RECORD
    private fun postRecord(posting: Int) = postBase + posting * LexiconFormat.POST_RECORD
    private fun wordRecord(word: Int) = wordBase + word * LexiconFormat.WORD_RECORD
    private fun u8(at: Int) = buf.get(at).toInt() and 0xFF
    private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF

    companion object {
        fun readingSyllable(packed: Int): Int = packed ushr 3
        fun readingTone(packed: Int): Int = packed and 7

        /** Maps [length] bytes of [channel] from [offset]; Android passes an AssetFileDescriptor's channel. */
        fun map(channel: FileChannel, offset: Long, length: Long, phonology: Phonology = Mandarin): LexiconReader =
            LexiconReader(channel.map(FileChannel.MapMode.READ_ONLY, offset, length), phonology)

        fun open(file: File, phonology: Phonology = Mandarin): LexiconReader =
            RandomAccessFile(file, "r").use { map(it.channel, 0, it.length(), phonology) }

        private fun tagName(tag: Int) = String(CharArray(4) { ((tag ushr (24 - 8 * it)) and 0xFF).toChar() })
    }
}
