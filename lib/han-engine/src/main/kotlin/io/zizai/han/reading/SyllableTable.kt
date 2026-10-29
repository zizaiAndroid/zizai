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
 * A sorted, immutable list of toneless syllable spellings. A syllable's id is its index in sorted order,
 * so ids are stable for as long as the inventory is (see [io.zizai.han.HanEngine.LEXICON_FORMAT]).
 */
class SyllableTable(spellings: Collection<String>) {
    private val sorted: Array<String> = spellings.toSortedSet().toTypedArray()

    /** Longest spelling, which bounds how far the parser looks ahead. */
    val maxLength: Int = sorted.maxOf { it.length }

    val size: Int get() = sorted.size

    /** The id of [spelling], or -1 when it isn't a syllable. */
    fun idOf(spelling: CharSequence): Int = sorted.binarySearch(spelling.toString()).let { if (it >= 0) it else -1 }

    fun spellingOf(id: Int): String = sorted[id]

    /** True if some syllable starts with [prefix] (including the syllable equal to it). */
    fun isPrefix(prefix: CharSequence): Boolean {
        val p = prefix.toString()
        val i = lowerBound(p)
        return i < sorted.size && sorted[i].startsWith(p)
    }

    /** Ids of every syllable starting with [prefix], in spelling order. */
    fun idsWithPrefix(prefix: CharSequence): IntRange {
        val p = prefix.toString()
        val from = lowerBound(p)
        var to = from
        while (to < sorted.size && sorted[to].startsWith(p)) to++
        return from until to
    }

    fun spellings(): List<String> = sorted.asList()

    private fun lowerBound(p: String): Int = sorted.binarySearch(p).let { if (it >= 0) it else -it - 1 }
}
