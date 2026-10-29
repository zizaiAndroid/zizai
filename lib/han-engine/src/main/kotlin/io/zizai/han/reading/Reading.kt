/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.reading

/**
 * A (phonology, syllable, tone) triple packed into an Int, so postings and lattices never box.
 * Tone 0 means "toneless": the reading of a syllable typed without a tone.
 *
 * Layout: bits 0–3 tone, bits 4–15 syllable id, bits 16–17 phonology id.
 */
@JvmInline
value class Reading(val packed: Int) {
    val phonology: Phonology get() = Phonology.byId(packed ushr 16)
    val syllable: Int get() = (packed ushr 4) and 0xFFF
    val tone: Int get() = packed and 0xF
    val isToneless: Boolean get() = tone == 0

    fun toneless(): Reading = Reading(packed and 0xF.inv())

    fun spelling(): String = phonology.syllables.spellingOf(syllable)

    /** Numbered spelling, e.g. `shi4`, or `shi` when toneless. */
    override fun toString(): String = if (isToneless) spelling() else "${spelling()}$tone"

    companion object {
        fun of(phonology: Phonology, syllable: Int, tone: Int): Reading {
            require(syllable in 0 until phonology.syllables.size) { "syllable $syllable" }
            require(tone in 0..phonology.maxTone) { "tone $tone" }
            return Reading((phonology.id shl 16) or (syllable shl 4) or tone)
        }

        /** Parses a numbered spelling (`shi4`, `lv3`, `lü3`, `nue4`, `ma5`, `ma`) as Mandarin. */
        fun parseMandarin(text: String): Reading? {
            val s = text.trim().lowercase()
            if (s.isEmpty()) return null
            val tone = s.last().digitToIntOrNull()
            val body = if (tone != null) s.dropLast(1) else s
            if (tone != null && tone !in 0..Mandarin.maxTone) return null
            val id = Mandarin.syllables.idOf(Pinyin.normalizeSpelling(body))
            if (id < 0) return null
            return of(Mandarin, id, tone ?: 0)
        }
    }
}
