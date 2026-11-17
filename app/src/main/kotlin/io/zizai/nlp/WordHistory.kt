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

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * The dictionary words the user has actually written, per language, so word completion can put them
 * first: having typed "helicopter" before, "hel" offers it ahead of "hello" and "help".
 *
 * Upstream's learning (issue #318) only keeps words the dictionary does *not* have — names, slang —
 * and returns early for every known word, so the strip's order among dictionary words never changed
 * however often one was used. This store is the other half: it records every finished word, but
 * [favourites] only ever hands back words the dictionary knows. A typo is not in the dictionary, so
 * however often it is typed it is never promoted here; unknown words stay upstream's business, with
 * its slip detection.
 *
 * Usage decays with a 30-day half-life, so this month's vocabulary beats last year's. Nothing is
 * recorded where upstream would not learn either: incognito, password fields (they have no composing
 * word), "Display suggestions" off, or word learning switched off — the callers check those.
 */
object WordHistory {
    /** How many favoured words one prefix may put ahead of the dictionary's own order. */
    const val MAX_FAVOURITES = 3

    /** Words kept per language; the least used go first when it overflows. */
    private const val MAX_WORDS = 3000

    private const val HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

    /** Below this decayed score a word no longer counts as used (about four half-lives after one use). */
    private const val MIN_SCORE = 0.06

    private const val SAVE_DELAY_MS = 3000L

    private class Entry(var score: Double, var lastMs: Long)

    private val byLang = HashMap<String, HashMap<String, Entry>>()
    private val dirty = HashSet<String>()
    private var saveScheduled = false

    /** One more use of the word with fold key [key]. A null [lang] records nothing. */
    fun note(context: Context, lang: String?, key: String, weight: Int = 1) {
        if (lang == null || key.isBlank() || key.length > 48) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            val words = load(context, lang)
            val entry = words.getOrPut(key) { Entry(0.0, now) }
            entry.score = decayed(entry, now) + weight.coerceIn(1, 4)
            entry.lastMs = now
            if (words.size > MAX_WORDS) prune(words, now)
            dirty += lang
        }
        scheduleSave(context)
    }

    /**
     * Up to [limit] fold keys the user has used that extend [foldedPrefix] and that [isKnown] accepts
     * (the dictionary), most used first.
     */
    fun favourites(
        context: Context,
        lang: String?,
        foldedPrefix: String,
        isKnown: (String) -> Boolean,
        limit: Int = MAX_FAVOURITES,
    ): List<String> {
        if (lang == null || foldedPrefix.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(this) {
            load(context, lang).entries.asSequence()
                .filter { (key, _) -> key.startsWith(foldedPrefix) }
                .map { (key, entry) -> key to decayed(entry, now) }
                .filter { (key, score) -> score >= MIN_SCORE && isKnown(key) }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    /** Forgets [key] in [lang], from the strip's long-press. */
    fun forget(context: Context, lang: String?, key: String) {
        if (lang == null) return
        synchronized(this) {
            if (load(context, lang).remove(key) != null) dirty += lang
        }
        scheduleSave(context)
    }

    internal fun decayedScore(score: Double, ageMs: Long): Double =
        score * 0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)

    private fun decayed(entry: Entry, now: Long) = decayedScore(entry.score, now - entry.lastMs)

    private fun prune(words: HashMap<String, Entry>, now: Long) {
        val keep = words.entries.sortedByDescending { decayed(it.value, now) }.take(MAX_WORDS * 9 / 10)
            .associate { it.key to it.value }
        words.clear()
        words.putAll(keep)
    }

    private fun fileFor(context: Context, lang: String) =
        File(File(context.filesDir, "zizai"), "word_history_${lang.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    /** Must hold the lock. */
    private fun load(context: Context, lang: String): HashMap<String, Entry> = byLang.getOrPut(lang) {
        val out = HashMap<String, Entry>()
        runCatching {
            val file = fileFor(context, lang)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                for (key in json.keys()) {
                    val arr = json.getJSONArray(key)
                    out[key] = Entry(arr.getDouble(0), arr.getLong(1))
                }
            }
        }
        out
    }

    private fun scheduleSave(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (saveScheduled) return
            saveScheduled = true
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread { save(app) }.start()
        }, SAVE_DELAY_MS)
    }

    private fun save(context: Context) {
        val snapshot: Map<String, Map<String, Pair<Double, Long>>> = synchronized(this) {
            saveScheduled = false
            val langs = dirty.toList()
            dirty.clear()
            langs.associateWith { lang -> byLang[lang].orEmpty().mapValues { it.value.score to it.value.lastMs } }
        }
        for ((lang, words) in snapshot) runCatching {
            val json = JSONObject()
            for ((key, v) in words) json.put(key, org.json.JSONArray().put(v.first).put(v.second))
            val file = fileFor(context, lang)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }
}
