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

import io.zizai.han.reading.Mandarin

/** Data invariants checked on every build (docs/ARCHITECTURE.md §6.8). Returns the problems found. */
object Invariants {
    const val GENERAL_STANDARD_SIZE = 8105

    fun check(lex: CoreLexicon, expectGeneralStandard: Boolean = true): List<String> {
        val problems = ArrayList<String>()
        val posted = lex.postings.mapIndexed { s, list -> s to list.mapTo(HashSet()) { it.codePoint } }.toMap()

        val standard = lex.chars.filter { it.tier > 0 }
        if (expectGeneralStandard && standard.size != GENERAL_STANDARD_SIZE) {
            problems += "General Standard table has ${standard.size} characters, expected $GENERAL_STANDARD_SIZE"
        }
        for (c in standard) {
            if (c.readings.isEmpty()) problems += "U+%04X has no reading".format(c.codePoint)
            for (r in c.readings) {
                val syllable = r.packed ushr 3
                if (c.codePoint !in posted.getValue(syllable)) {
                    problems += "U+%04X missing from postings of %s".format(c.codePoint, Mandarin.syllables.spellingOf(syllable))
                }
            }
        }
        val empty = lex.postings.indices.filter { lex.postings[it].isEmpty() }.map(Mandarin.syllables::spellingOf)
        if (empty.isNotEmpty()) problems += "syllables without postings: $empty"
        return problems
    }
}
