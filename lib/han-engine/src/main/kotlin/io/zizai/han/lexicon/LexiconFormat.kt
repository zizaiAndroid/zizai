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

/**
 * Layout of `han-core.bin` (docs/ARCHITECTURE.md §6.8), shared by the writer in `:tools:han-dict-builder`
 * and [LexiconReader]. Everything is big-endian and every section starts on a 4-byte boundary.
 *
 * ```text
 * header   "ZZHN", u32 version, u32 sectionCount, u32 reserved,
 *          sectionCount × { u32 tag, u32 offset, u32 length }
 * SYLL     u32 n, n × { u8 length, ASCII spelling }     must equal the compiled syllable table
 * CHAR     u32 n, n × 12 bytes { u32 codePoint, u32 firstReading, u8 readingCount, u8 tier, u8 strokes,
 *          u8 flags }, sorted by code point
 * READ     u32 n, n × { u16 reading, u16 weight }       per character, best reading first
 * PIDX     u32 syllables, per syllable { u32 firstPosting, u32 count }
 * POST     u32 n, n × 8 bytes { u32 codePoint, u8 toneMask, u8 tier, u8 flags, u8 score }, best first
 * WORD     u32 n, n × 16 bytes { u32 firstSyllable, u8 syllableCount, u8 score, u16 reserved,
 *          u32 textOffset, u32 textLength }, sorted by syllable sequence, then score descending
 * WSYL     u32 n, n × u16 syllable id
 * WTXT     u32 n, n × u32 word index, sorted by the words' UTF-8 bytes (unsigned)
 * STRS     UTF-8 text of every word
 * ```
 *
 * A packed reading is `syllable shl 3 or tone`; tone 0 means unknown. A tone mask has bit `t` set for
 * each tone `t` (1–5) the character has with that syllable; 0 means unknown.
 */
object LexiconFormat {
    const val MAGIC = 0x5A5A484E // "ZZHN"
    const val HEADER_SIZE = 16
    const val SECTION_ENTRY_SIZE = 12

    const val SYLL = 0x53594C4C
    const val CHAR = 0x43484152
    const val READ = 0x52454144
    const val PIDX = 0x50494458
    const val POST = 0x504F5354
    const val WORD = 0x574F5244
    const val WSYL = 0x5753594C
    const val WTXT = 0x57545854
    const val STRS = 0x53545253

    const val CHAR_RECORD = 12
    const val READ_RECORD = 4
    const val PIDX_RECORD = 8
    const val POST_RECORD = 8
    const val WORD_RECORD = 16

    /** Tier values: General Standard Chinese Characters (通用规范汉字表) levels, 0 when not listed. */
    const val TIER_NONE = 0

    /** In GB 2312. */
    const val FLAG_GB2312 = 1

    /** In Big5 level 1 (常用字, A440–C67E). */
    const val FLAG_BIG5_LEVEL1 = 2

    /** In Big5. */
    const val FLAG_BIG5 = 4

    /** A simplified form: it has a different traditional variant. */
    const val FLAG_SIMPLIFIED = 8

    /** A traditional form: it has a different simplified variant. */
    const val FLAG_TRADITIONAL = 16

    fun packReading(syllable: Int, tone: Int): Int = (syllable shl 3) or tone

    fun tag(name: String): Int = name.fold(0) { acc, c -> (acc shl 8) or c.code }
}
