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
 * A sound system with its own syllable inventory and tones (docs/ARCHITECTURE.md §6.2, "Readings model").
 * Mandarin comes first; Cantonese is a second table, not a second engine.
 */
sealed interface Phonology {
    /** Stable id stored in lexicon files, 0..3. */
    val id: Int

    /** Highest tone number. Tone 0 means "toneless": a syllable typed without a tone. */
    val maxTone: Int

    val syllables: SyllableTable

    companion object {
        fun byId(id: Int): Phonology = when (id) {
            Mandarin.id -> Mandarin
            Cantonese.id -> Cantonese
            else -> throw IllegalArgumentException("Unknown phonology id $id")
        }
    }
}

/** Mandarin with Hanyu Pinyin spellings. Tones 1–4, and 5 for the neutral tone (轻声). */
data object Mandarin : Phonology {
    override val id = 0
    override val maxTone = 5
    const val NEUTRAL_TONE = 5
    override val syllables: SyllableTable = SyllableTable(MandarinInventory.SPELLINGS)
}

/**
 * Cantonese with Jyutping spellings, tones 1–6 (P2-10). For now it only gives characters their
 * Cantonese readings, so the flip works on Cantonese dictation; Jyutping typing comes in Phase 3.
 */
data object Cantonese : Phonology {
    override val id = 1
    override val maxTone = 6
    override val syllables: SyllableTable = SyllableTable(CantoneseInventory.SPELLINGS)
}
