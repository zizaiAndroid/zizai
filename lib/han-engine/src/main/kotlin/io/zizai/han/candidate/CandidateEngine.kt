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

import io.zizai.han.lexicon.LexiconReader
import io.zizai.han.parse.EdgeKind
import io.zizai.han.parse.SyllableEdge
import io.zizai.han.parse.SyllableLattice
import java.util.PriorityQueue

/**
 * Assembles candidates for a Pinyin buffer (docs/ARCHITECTURE.md §6.2, "Candidate assembly"), lazily:
 *
 * 1. user phrases for the whole input;
 * 2. the best whole-input sentence (word-unigram Viterbi), when it spans several syllables;
 * 3. everything that covers the whole input: words along any segmentation, and for one-syllable input
 *    every character, merged by score with a penalty for segmentations worse than the best;
 * 4. words for the longest prefix, then shorter prefixes (partial commit);
 * 5. every character for the first syllable.
 *
 * Duplicates are dropped. Nothing is materialized beyond what the caller pulls, except the word lists
 * of stage 3 and 4, which are small.
 */
class CandidateEngine(
    private val lexicon: LexiconReader,
    val user: UserDictionary = UserDictionary.None,
) {
    fun candidates(lattice: SyllableLattice, filter: HomophoneFilter = HomophoneFilter()): Sequence<Candidate> = sequence {
        val n = lattice.length
        if (n == 0) return@sequence
        val seen = HashSet<String>()
        suspend fun SequenceScope<Candidate>.emit(c: Candidate) {
            if (!user.isBlocked(c.text) && seen.add(c.text)) yield(c)
        }

        for (c in user.phrases(lattice)) emit(c)

        val best = lattice.bestPaths(1).firstOrNull() ?: return@sequence
        val sentence = if (best.edges.size >= 2) bestSentence(lattice, filter) else null

        val bestTo = cheapestCosts(lattice)
        val wholeStreams = ArrayList<Iterator<Candidate>>()
        val prefixWords = ArrayList<WordHit>()
        forEachPath(lattice, 0, best.cost + MAX_EXTRA_COST) { edges, depth, cost ->
            val end = edges[depth - 1].end
            val whole = skipApostrophes(lattice.input, end) == n
            // Compared with the cheapest way to reach the same point, so abbreviations rank below full
            // syllables, and prefix readings much worse than that (`nihao` read as ni'h'a) are dropped.
            val extra = cost - bestTo[end]
            val penalty = extra * PATH_PENALTY
            if (depth == 1) {
                if (whole && contributesCharacters(lattice, edges[0])) {
                    wholeStreams += characters(edges[0], filter, penalty, n)
                }
            } else if (whole) {
                wholeStreams += words(edges, depth, penalty, n).asSequence().map(::toCandidate).iterator()
            } else if (extra <= MAX_PREFIX_EXTRA_COST) {
                prefixWords += words(edges, depth, penalty, end)
            }
        }
        val whole = mergeSorted(wholeStreams)
        // The sentence leads, unless a whole-input word exists: then it follows the best of those, and only
        // if it is built from words (a run of single characters, 为心 for weixin, is noise next to a word).
        if (sentence != null && !(whole.hasNext())) emit(sentence)
        if (whole.hasNext()) {
            emit(whole.next())
            sentence?.takeIf { it.wordParts > 0 }?.let { emit(it) }
        }
        for (c in whole) emit(c)

        // Longest prefix first; within one length, by score.
        prefixWords.sortWith(compareByDescending<WordHit> { it.end }.thenByDescending { it.score })
        for (h in prefixWords) emit(toCandidate(h))

        if (best.edges.size >= 2 || best.edges.first().end != n) {
            val first = best.edges.first()
            if (first.kind != EdgeKind.RAW) for (c in characters(first, filter, 0.0, first.end)) emit(c)
        }
        if (best.edges.any { it.kind == EdgeKind.RAW }) emit(Candidate(lattice.input, n, CandidateKind.RAW, 0.0))
    }

    /** Every character for [first]..[last] syllables, merged by score: the exhaustive homophone list. */
    fun homophones(first: Int, last: Int, filter: HomophoneFilter, end: Int = 0): Iterator<Candidate> =
        mergeSorted((first..last).map { syllableCharacters(it, filter, 0.0, end) })

    /** The best conversion of the whole input as one string, or null if nothing but raw letters. */
    fun bestSentence(lattice: SyllableLattice, filter: HomophoneFilter = HomophoneFilter()): Candidate? {
        val input = lattice.input
        val n = input.length
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val back = arrayOfNulls<Pair<Int, String>>(n + 1)
        val start0 = skipApostrophes(input, 0)
        cost[start0] = 0.0
        val bestChar = HashMap<Int, Int>()
        val cheapest = cheapestCosts(lattice)
        for (i in 0..n) {
            if (cost[i] == Double.POSITIVE_INFINITY || i == n) continue
            forEachPath(lattice, i, Double.POSITIVE_INFINITY, cheapest) { edges, depth, _ ->
                val e = skipApostrophes(input, edges[depth - 1].end)
                val unit: Pair<String, Double>? = if (depth == 1) {
                    val edge = edges[0]
                    if (edge.kind == EdgeKind.RAW) {
                        input.substring(edge.start, edge.end) to UNIT_COST + RAW_COST
                    } else {
                        bestCharacter(edge, filter, bestChar)?.let { (cp, score) ->
                            String(Character.toChars(cp)) to UNIT_COST - score / 16.0 + (edge.cost - 1.0) * PATH_PENALTY
                        }
                    }
                } else {
                    bestWord(edges, depth)?.let { (w, score) ->
                        val edgePenalty = (0 until depth).sumOf { edges[it].cost - 1.0 } * PATH_PENALTY
                        lexicon.wordText(w) to UNIT_COST - score / 16.0 + edgePenalty
                    }
                }
                if (unit != null && cost[i] + unit.second < cost[e]) {
                    cost[e] = cost[i] + unit.second
                    back[e] = i to unit.first
                }
            }
        }
        if (cost[n] == Double.POSITIVE_INFINITY) return null
        val parts = ArrayList<String>()
        var p = n
        while (p != start0) {
            val (from, text) = back[p]!!
            parts += text
            p = from
        }
        parts.reverse()
        // One unit is just a word or character, listed anyway; only raw letters is no conversion at all.
        if (parts.size < 2 || parts.all { part -> part.all { it in 'a'..'z' } }) return null
        return Candidate(
            parts.joinToString(""), n, CandidateKind.SENTENCE, -cost[n],
            wordParts = parts.count { it.codePointCount(0, it.length) > 1 },
        )
    }

    // ---- Internals ----

    /** Cheapest lattice cost to reach each position from the start. */
    private fun cheapestCosts(lattice: SyllableLattice): DoubleArray {
        val n = lattice.length
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        cost[0] = 0.0
        for (i in 0..n) {
            if (cost[i] == Double.POSITIVE_INFINITY) continue
            val from = skipApostrophes(lattice.input, i)
            if (from != i) cost[from] = minOf(cost[from], cost[i])
            for (e in lattice.edgesFrom(from)) cost[e.end] = minOf(cost[e.end], cost[i] + e.cost)
        }
        return cost
    }

    private val firsts = IntArray(MAX_WORD_SYLLABLES)
    private val lasts = IntArray(MAX_WORD_SYLLABLES)

    /** A word found for a path, before its text is decoded: only the ones shown ever are. */
    private class WordHit(val word: Int, val score: Double, val end: Int)

    private fun toCandidate(h: WordHit) = Candidate(lexicon.wordText(h.word), h.end, CandidateKind.WORD, h.score)

    /** Words exactly matching the path, best first, scored with [penalty]. */
    private fun words(edges: EdgeStack, depth: Int, penalty: Double, end: Int): List<WordHit> {
        if ((0 until depth).any { edges[it].kind == EdgeKind.RAW }) return emptyList()
        for (i in 0 until depth) {
            firsts[i] = edges[i].first
            lasts[i] = edges[i].last
        }
        val out = ArrayList<WordHit>()
        lexicon.forEachWord(firsts, lasts, depth, true) { w ->
            out += WordHit(w, lexicon.wordScore(w) / 16.0 - penalty, end)
            out.size < MAX_WORDS_PER_PATH
        }
        out.sortByDescending { it.score }
        return out
    }

    private fun bestWord(edges: EdgeStack, depth: Int): Pair<Int, Int>? {
        if ((0 until depth).any { edges[it].kind == EdgeKind.RAW }) return null
        val f = IntArray(depth) { edges[it].first }
        val l = IntArray(depth) { edges[it].last }
        var best = -1
        var bestScore = -1
        lexicon.forEachWord(f, l, depth, true) { w ->
            val s = lexicon.wordScore(w)
            if (s > bestScore) {
                best = w
                bestScore = s
            }
            true
        }
        return if (best < 0) null else best to bestScore
    }

    private fun bestCharacter(edge: SyllableEdge, filter: HomophoneFilter, cache: HashMap<Int, Int>): Pair<Int, Int>? {
        var bestCp = -1
        var bestScore = -1
        for (s in edge.syllables) {
            val p = cache.getOrPut(s) { firstAccepted(s, filter) }
            if (p >= 0 && lexicon.postingScore(p) > bestScore) {
                bestScore = lexicon.postingScore(p)
                bestCp = lexicon.postingCodePoint(p)
            }
        }
        return if (bestCp < 0) null else bestCp to bestScore
    }

    private fun firstAccepted(syllable: Int, filter: HomophoneFilter): Int {
        val start = lexicon.postingStart(syllable)
        for (p in start until start + lexicon.postingCount(syllable)) {
            if (accepts(p, filter)) return p
        }
        return -1
    }

    private fun accepts(p: Int, filter: HomophoneFilter) = filter.accepts(
        lexicon.postingCodePoint(p), lexicon.postingToneMask(p), lexicon.postingTier(p), lexicon.postingFlags(p),
    )

    private fun characters(edge: SyllableEdge, filter: HomophoneFilter, penalty: Double, end: Int): Iterator<Candidate> =
        mergeSorted(edge.syllables.map { syllableCharacters(it, filter, penalty, end) })

    private fun syllableCharacters(syllable: Int, filter: HomophoneFilter, penalty: Double, end: Int) = iterator {
        val start = lexicon.postingStart(syllable)
        for (p in start until start + lexicon.postingCount(syllable)) {
            if (!accepts(p, filter)) continue
            val cp = lexicon.postingCodePoint(p)
            yield(
                Candidate(
                    String(Character.toChars(cp)), end, CandidateKind.CHAR,
                    lexicon.postingScore(p) / 16.0 - penalty, lexicon.postingToneMask(p),
                ),
            )
        }
    }

    /**
     * A complete last syllable can also be read as the start of a longer one (`xia` → xiang); that
     * reading adds completions as words, but not its characters, which would bury the syllable's own.
     */
    private fun contributesCharacters(lattice: SyllableLattice, edge: SyllableEdge): Boolean =
        edge.kind != EdgeKind.RAW && !(edge.kind == EdgeKind.PREFIX &&
            lattice.edgesFrom(edge.start).any { it.end == edge.end && it.isComplete })

    /** The longest word in the lexicon, in syllables: no path deeper than that can match a word. */
    private val maxDepth: Int by lazy {
        var m = 1
        for (w in 0 until lexicon.wordCount) m = maxOf(m, lexicon.wordSyllableCount(w))
        minOf(m, MAX_WORD_SYLLABLES)
    }

    /**
     * Depth-first over segmentations starting at [from], as deep as the longest word. With [cheapest]
     * (the lattice's cheapest cost to each position), a path is dropped once it costs more than
     * [MAX_EXTRA_COST] over the cheapest way between its ends.
     */
    private fun forEachPath(
        lattice: SyllableLattice,
        from: Int,
        maxCost: Double,
        cheapest: DoubleArray? = null,
        visit: (EdgeStack, Int, Double) -> Unit,
    ) {
        val stack = EdgeStack(MAX_WORD_SYLLABLES)
        val base = cheapest?.get(from) ?: 0.0
        fun walk(pos: Int, depth: Int, cost: Double) {
            if (depth == maxDepth) return
            for (edge in lattice.edgesFrom(skipApostrophes(lattice.input, pos))) {
                val c = cost + edge.cost
                if (c > maxCost) continue
                if (cheapest != null && c - (cheapest[edge.end] - base) > MAX_EXTRA_COST) continue
                stack[depth] = edge
                visit(stack, depth + 1, c)
                if (edge.end < lattice.length) walk(edge.end, depth + 1, c)
            }
        }
        walk(from, 0, 0.0)
    }

    companion object {
        const val MAX_WORD_SYLLABLES = 8
        const val MAX_WORDS_PER_PATH = 200

        /** Score lost per unit of lattice cost above the best segmentation (in log-frequency units). */
        const val PATH_PENALTY = 2.0

        /** Paths costing more than the best by this much are not searched for words. */
        const val MAX_EXTRA_COST = 4.0

        /** Prefix words whose reading costs more than the cheapest to the same point by this are dropped. */
        const val MAX_PREFIX_EXTRA_COST = 1.0

        /** Viterbi cost of one unit (word or character); more units cost more, so long words win. */
        const val UNIT_COST = 20.0
        const val RAW_COST = 10.0

        fun skipApostrophes(input: String, pos: Int): Int {
            var p = pos
            while (p < input.length && input[p] == '\'') p++
            return p
        }

        /** Merges iterators that are each sorted by descending score; ties keep iterator order. */
        fun mergeSorted(streams: List<Iterator<Candidate>>): Iterator<Candidate> {
            if (streams.size == 1) return streams[0]
            class Head(val c: Candidate, val index: Int)
            val queue = PriorityQueue<Head>(maxOf(1, streams.size), compareByDescending<Head> { it.c.score }.thenBy { it.index })
            streams.forEachIndexed { i, it -> if (it.hasNext()) queue += Head(it.next(), i) }
            return iterator {
                while (queue.isNotEmpty()) {
                    val h = queue.poll()
                    yield(h.c)
                    val s = streams[h.index]
                    if (s.hasNext()) queue += Head(s.next(), h.index)
                }
            }
        }
    }
}

/** The edges of the path being walked; only the first `depth` entries are meaningful. */
private class EdgeStack(capacity: Int) {
    private val edges = arrayOfNulls<SyllableEdge>(capacity)
    operator fun get(i: Int): SyllableEdge = edges[i]!!
    operator fun set(i: Int, edge: SyllableEdge) {
        edges[i] = edge
    }
}

/** Pulls candidates page by page and keeps what it pulled, so paging back is free. */
class CandidatePager(candidates: Sequence<Candidate>, val pageSize: Int) {
    private val source = candidates.iterator()
    private val pulled = ArrayList<Candidate>()

    /** How many candidates have been pulled from the source so far. */
    val pulledCount: Int get() = pulled.size

    fun page(index: Int): List<Candidate> {
        val from = index * pageSize
        while (pulled.size < from + pageSize && source.hasNext()) pulled += source.next()
        return if (from >= pulled.size) emptyList() else pulled.subList(from, minOf(from + pageSize, pulled.size))
    }

    fun hasPage(index: Int): Boolean = page(index).isNotEmpty()
}
