/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.nlp

/**
 * A fix for a typo that happens to spell a rare dictionary word: `aer` → `are`, `teh` → `the`,
 * `nad` → `and`, `thier` → `their`.
 *
 * Upstream corrects only words the dictionary lacks, and its 50,000-word English list has `aer`, `teh`,
 * `nad` and `thier` in it, so the commonest slips got completions of themselves (aerial, aerospace…)
 * and never the word that was meant. Here a known word is offered one fix when it is rare and one edit
 * away (a swapped pair of letters, or one letter replaced, dropped or added) from a word that is far
 * more common. The fix is only offered in the strip, after the typed word, and is never taken on
 * Space: a rare word can still be the one the user meant.
 *
 * Frequencies are the dictionary's 128..255 scale. On the English list the thresholds below fire on
 * about one known word in eight, nearly all of them obscure (names, abbreviations, archaic words).
 */
object RareWordFix {
    /** A typed word at or above this frequency is common enough to be left alone. */
    const val RARE_MAX_FREQ = 170

    /** The fix must be at least this common… */
    const val FIX_MIN_FREQ = 210

    /** …and at least this much more common than the typed word. */
    const val MIN_GAP = 60

    const val MIN_LENGTH = 3

    /**
     * The fold key of the best fix for [folded], or null. [freq] maps fold keys to frequency and
     * [alphabet] is the set of letters edits may introduce.
     */
    fun fixFor(folded: String, freq: Map<String, Int>, alphabet: Set<Char>): String? {
        if (folded.length < MIN_LENGTH) return null
        val typedFreq = freq[folded] ?: return null
        if (typedFreq > RARE_MAX_FREQ) return null
        val floor = maxOf(FIX_MIN_FREQ, typedFreq + MIN_GAP)
        var best: String? = null
        var bestFreq = floor - 1
        fun consider(candidate: String) {
            if (candidate == folded) return
            val f = freq[candidate] ?: return
            if (f > bestFreq) {
                best = candidate
                bestFreq = f
            }
        }
        val n = folded.length
        for (i in 0..n) {
            val head = folded.substring(0, i)
            val tail = folded.substring(i)
            if (tail.isNotEmpty()) consider(head + tail.substring(1))
            if (tail.length > 1) consider(head + tail[1] + tail[0] + tail.substring(2))
            for (c in alphabet) {
                if (tail.isNotEmpty() && c != tail[0]) consider(head + c + tail.substring(1))
                consider(head + c + tail)
            }
        }
        return best
    }
}
