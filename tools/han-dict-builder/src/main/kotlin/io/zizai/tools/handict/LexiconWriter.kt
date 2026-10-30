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
import io.zizai.han.lexicon.LexiconFormat
import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Phonology
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Serializes a [CoreLexicon] in the layout documented on [LexiconFormat]. */
object LexiconWriter {
    fun write(lex: CoreLexicon, phonology: Phonology = Mandarin): ByteArray {
        val sections = linkedMapOf<Int, ByteArray>()
        sections[LexiconFormat.SYLL] = section { out ->
            val table = phonology.syllables
            out.writeInt(table.size)
            for (s in table.spellings()) {
                out.writeByte(s.length)
                out.writeBytes(s)
            }
        }

        sections[LexiconFormat.CHAR] = section { out ->
            out.writeInt(lex.chars.size)
            var first = 0
            for (c in lex.chars) {
                out.writeInt(c.codePoint)
                out.writeInt(first)
                out.writeByte(c.readings.size.also { check(it <= 255) })
                out.writeByte(c.tier)
                out.writeByte(c.strokes.coerceAtMost(255))
                out.writeByte(c.flags)
                first += c.readings.size
            }
        }
        sections[LexiconFormat.READ] = section { out ->
            out.writeInt(lex.chars.sumOf { it.readings.size })
            for (c in lex.chars) for (r in c.readings) {
                out.writeShort(r.packed)
                out.writeShort(r.weight)
            }
        }

        sections[LexiconFormat.PIDX] = section { out ->
            out.writeInt(lex.postings.size)
            var first = 0
            for (p in lex.postings) {
                out.writeInt(first)
                out.writeInt(p.size)
                first += p.size
            }
        }
        sections[LexiconFormat.POST] = section { out ->
            out.writeInt(lex.postings.sumOf { it.size })
            for (list in lex.postings) for (p in list) {
                out.writeInt(p.codePoint)
                out.writeByte(p.toneMask)
                out.writeByte(p.tier)
                out.writeByte(p.flags)
                out.writeByte(p.score)
            }
        }

        val strings = ByteArrayOutputStream()
        val textOffsets = IntArray(lex.words.size)
        val textLengths = IntArray(lex.words.size)
        lex.words.forEachIndexed { i, w ->
            val bytes = w.text.toByteArray(Charsets.UTF_8)
            textOffsets[i] = strings.size()
            textLengths[i] = bytes.size
            strings.write(bytes)
        }
        sections[LexiconFormat.WORD] = section { out ->
            out.writeInt(lex.words.size)
            var first = 0
            lex.words.forEachIndexed { i, w ->
                out.writeInt(first)
                out.writeByte(w.syllables.size)
                out.writeByte(w.score)
                out.writeShort(0)
                out.writeInt(textOffsets[i])
                out.writeInt(textLengths[i])
                first += w.syllables.size
            }
        }
        sections[LexiconFormat.WSYL] = section { out ->
            out.writeInt(lex.words.sumOf { it.syllables.size })
            for (w in lex.words) for (s in w.syllables) out.writeShort(s)
        }
        sections[LexiconFormat.WTXT] = section { out ->
            out.writeInt(lex.words.size)
            // UTF-8 byte order, so the reader can binary-search without decoding.
            val utf8 = lex.words.map { it.text.toByteArray(Charsets.UTF_8) }
            lex.words.indices.sortedWith { a, b -> java.util.Arrays.compareUnsigned(utf8[a], utf8[b]).takeIf { it != 0 } ?: (a - b) }
                .forEach { out.writeInt(it) }
        }
        sections[LexiconFormat.STRS] = strings.toByteArray()

        val result = ByteArrayOutputStream()
        val out = DataOutputStream(result)
        out.writeInt(LexiconFormat.MAGIC)
        out.writeInt(HanEngine.LEXICON_FORMAT)
        out.writeInt(sections.size)
        out.writeInt(0)
        var offset = align(LexiconFormat.HEADER_SIZE + sections.size * LexiconFormat.SECTION_ENTRY_SIZE)
        for ((tag, bytes) in sections) {
            out.writeInt(tag)
            out.writeInt(offset)
            out.writeInt(bytes.size)
            offset = align(offset + bytes.size)
        }
        for (bytes in sections.values) {
            while (result.size() % 4 != 0) out.writeByte(0)
            out.write(bytes)
        }
        while (result.size() % 4 != 0) out.writeByte(0)
        return result.toByteArray()
    }

    private fun align(n: Int) = (n + 3) and 3.inv()

    private fun section(block: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }
}
