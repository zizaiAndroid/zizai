/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.parse


enum class EdgeKind(val cost: Double) {
    /** A complete syllable. */
    FULL(1.0),

    /** A complete but rare interjection syllable (m, n, ng, hm, hng); kept, but rarely the best split. */
    RARE(3.0),

    /** An incomplete last syllable (`zhon`), or a complete one read as the start of a longer one. */
    PREFIX(1.6),

    /** An initial standing for a whole syllable (简拼): `z` in `zg`. */
    INITIAL(2.5),

    /** A letter no syllable can explain. Keeps every input parseable; the letter is passed through. */
    RAW(10.0),
}

/**
 * One way to read [input] from [start] (inclusive) to [end] (exclusive): the syllables with ids
 * [first]..[last] in the table's spelling order. A [EdgeKind.FULL] edge has exactly one syllable.
 */
data class SyllableEdge(
    val start: Int,
    val end: Int,
    val kind: EdgeKind,
    val first: Int,
    val last: Int,
    val cost: Double = kind.cost,
) {
    val syllables: IntRange get() = first..last
    val isComplete: Boolean get() = kind == EdgeKind.FULL || kind == EdgeKind.RARE
}

/** A segmentation of the whole input, from the lattice's best-first list. */
data class SyllablePath(val edges: List<SyllableEdge>, val cost: Double) {
    val isComplete: Boolean get() = edges.all { it.isComplete }

    /** Spelling with apostrophes between syllables: `xi'an`, or `zhong'g` with incomplete parts. */
    fun spelling(input: String): String = edges.joinToString("'") { input.substring(it.start, it.end) }
}

/**
 * The DAG of every way to split a Pinyin buffer into syllables (docs/ARCHITECTURE.md §6.2). Nodes are
 * character positions in [input]; apostrophes are fixed boundaries that no edge crosses.
 */
class SyllableLattice internal constructor(
    val input: String,
    private val outgoing: Array<List<SyllableEdge>>,
) {
    val length: Int get() = input.length

    fun edgesFrom(position: Int): List<SyllableEdge> = if (position < outgoing.size) outgoing[position] else emptyList()

    fun allEdges(): List<SyllableEdge> = outgoing.flatMap { it }

    /** Up to [k] whole-input paths, cheapest first. Ties keep the order edges were found in. */
    fun bestPaths(k: Int): List<SyllablePath> {
        val n = input.length
        val best = Array(n + 1) { ArrayList<Partial>(k) }
        best[0].add(Partial(0.0, null, null))
        for (i in 0 until n) {
            val here = best[i]
            if (here.isEmpty()) continue
            if (input[i] == '\'') {
                for (p in here) offer(best[i + 1], p, k)
                continue
            }
            for (edge in outgoing[i]) {
                for (p in here) offer(best[edge.end], Partial(p.cost + edge.cost, edge, p), k)
            }
        }
        return best[n].map { it.toPath() }
    }

    /** True if [syllables], in order, are a complete segmentation of the whole input. */
    fun containsPath(syllables: List<Int>): Boolean {
        fun walk(pos: Int, index: Int): Boolean {
            var p = pos
            while (p < input.length && input[p] == '\'') p++
            if (index == syllables.size) return p == input.length
            return edgesFrom(p).any { it.isComplete && it.first == syllables[index] && walk(it.end, index + 1) }
        }
        return walk(0, 0)
    }

    override fun toString(): String = allEdges().joinToString(prefix = "Lattice($input: ", postfix = ")") {
        "${input.substring(it.start, it.end)}@${it.start}:${it.kind}"
    }

    private class Partial(val cost: Double, val edge: SyllableEdge?, val prev: Partial?) {
        fun toPath(): SyllablePath {
            val edges = ArrayList<SyllableEdge>()
            var p: Partial? = this
            while (p?.edge != null) {
                edges.add(p.edge!!)
                p = p.prev
            }
            edges.reverse()
            return SyllablePath(edges, cost)
        }
    }

    private fun offer(list: ArrayList<Partial>, p: Partial, k: Int) {
        var i = list.size
        while (i > 0 && list[i - 1].cost > p.cost) i--
        if (i >= k) return
        list.add(i, p)
        if (list.size > k) list.removeAt(list.size - 1)
    }
}
