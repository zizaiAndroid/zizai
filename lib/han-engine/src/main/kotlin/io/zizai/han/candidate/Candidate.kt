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

import io.zizai.han.lexicon.LexiconFormat

enum class CandidateKind {
    /** A phrase the user made (造词). */
    USER,

    /** The best conversion of the whole input, built from several words. */
    SENTENCE,

    /** A lexicon word. */
    WORD,

    /** A single character. */
    CHAR,

    /** Letters passed through as typed. */
    RAW,
}

/**
 * One entry in the candidate list. Selecting it commits [text] and consumes the input up to [end]; if
 * that's short of the input's length, the rest stays composing (partial commit).
 *
 * [score] orders candidates within a stage: roughly a log frequency, minus penalties for abbreviations.
 * [toneMask] is set for characters (bit t for tone t), 0 otherwise.
 */
data class Candidate(
    val text: String,
    val end: Int,
    val kind: CandidateKind,
    val score: Double,
    val toneMask: Int = 0,
    /** For a sentence: how many of its parts are words rather than single characters. */
    val wordParts: Int = 0,
) {
    /** 以词定字: the word's first character, still consuming the whole word's input. */
    fun firstCharacter(): Candidate = copy(text = String(Character.toChars(text.codePointAt(0))), kind = CandidateKind.CHAR)

    /** 以词定字: the word's last character, still consuming the whole word's input. */
    fun lastCharacter(): Candidate =
        copy(text = String(Character.toChars(text.codePointBefore(text.length))), kind = CandidateKind.CHAR)
}

enum class CharsetTier {
    /** 通用规范汉字表 level 1: the 3,500 common characters. */
    COMMON,

    /** 通用规范汉字表 levels 1–3: 8,105 characters. */
    GENERAL,

    /** Big5 level 1: 5,401 common Traditional characters. */
    BIG5_COMMON,

    /** All of Big5: 13,053 characters. */
    BIG5,

    /** Everything with a reading. */
    ALL,
}

enum class ScriptFilter { SIMPLIFIED, TRADITIONAL, BOTH }

/**
 * Filters for single characters (docs/ARCHITECTURE.md §6.2, "Exhaustive homophones"). [tone] 0 means all
 * tones. [renderable] is filled in on Android with `Paint.hasGlyph`; null accepts everything.
 */
data class HomophoneFilter(
    val tone: Int = 0,
    val tier: CharsetTier = CharsetTier.ALL,
    val script: ScriptFilter = ScriptFilter.SIMPLIFIED,
    val renderable: ((Int) -> Boolean)? = null,
) {
    fun accepts(codePoint: Int, toneMask: Int, tier: Int, flags: Int): Boolean {
        if (tone != 0 && (toneMask and (1 shl tone)) == 0) return false
        val tierOk = when (this.tier) {
            CharsetTier.COMMON -> tier == 1
            CharsetTier.GENERAL -> tier in 1..3
            CharsetTier.BIG5_COMMON -> flags and LexiconFormat.FLAG_BIG5_LEVEL1 != 0
            CharsetTier.BIG5 -> flags and LexiconFormat.FLAG_BIG5 != 0
            CharsetTier.ALL -> true
        }
        if (!tierOk) return false
        val simplified = flags and LexiconFormat.FLAG_SIMPLIFIED != 0
        val traditional = flags and LexiconFormat.FLAG_TRADITIONAL != 0
        val scriptOk = when (script) {
            ScriptFilter.SIMPLIFIED -> !traditional || simplified
            ScriptFilter.TRADITIONAL -> !simplified || traditional
            ScriptFilter.BOTH -> true
        }
        return scriptOk && renderable?.invoke(codePoint) != false
    }
}

/** What the user taught the keyboard: learned words and phrases, and blocked candidates (P2-02). */
interface UserDictionary {
    /** The user's words and phrases that read exactly as the whole input, best first. */
    fun phrases(lattice: io.zizai.han.parse.SyllableLattice): List<Candidate>

    /** Candidates the user removed (Ctrl+Delete); they never show. */
    fun isBlocked(text: String): Boolean = false

    /** [text] was committed with these syllables; [assembled] if it was put together from several picks. */
    fun learn(text: String, syllables: IntArray, assembled: Boolean) {}

    fun block(text: String) {}

    object None : UserDictionary {
        override fun phrases(lattice: io.zizai.han.parse.SyllableLattice): List<Candidate> = emptyList()
    }
}
