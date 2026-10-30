/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.zhtext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * A character or word the model thinks was misheard, at UTF-16 offsets [start]..[end] of the checked
 * text, with replacements to offer first. Nothing is changed until the user confirms one.
 */
data class Suspect(
    val start: Int,
    val end: Int,
    val original: String,
    val suggestions: List<String>,
    val confidence: Double,
    val hint: String?,
)

/**
 * The homophone-proofreading contract (docs/ARCHITECTURE.md §6.7, P2-08): the model reports suspects as
 * JSON and must not rewrite the text. Every suspect is checked against the text; anything that doesn't
 * hold up is dropped, so a confused model can at worst report nothing.
 */
object Proofreading {
    const val MAX_SUSPECTS = 20

    val SYSTEM_PROMPT = """
        You check Chinese text produced by speech recognition for homophone errors (同音错别字): a
        character or word that sounds right but is the wrong one in context, such as 在/再, 的/地/得,
        他/她, 以/已, 做/作. Do not rewrite, rephrase or correct anything else.
        Answer with JSON only, no prose, in exactly this form:
        {"suspects":[{"start":12,"end":13,"original":"在","suggestions":["再"],"confidence":0.86,"hint":"再见"}]}
        start and end are code-point offsets into the text (end exclusive); original is the text at that
        span exactly; suggestions are replacements that sound the same or nearly so, best first;
        confidence is 0 to 1; hint is the corrected word in context. With nothing suspicious, answer
        {"suspects":[]}.
    """.trimIndent()

    fun userPrompt(text: String) = "Text:\n$text"

    /** The suspects in [answer] that check out against [text], sorted, without overlaps, at most [MAX_SUSPECTS]. */
    fun parse(text: String, answer: String): List<Suspect> {
        val root = runCatching { Json.parseToJsonElement(extractJson(answer)) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val list = root["suspects"] as? JsonArray ?: return emptyList()
        val codePoints = text.codePointCount(0, text.length)
        val out = ArrayList<Suspect>()
        for (item in list) {
            val o = item as? JsonObject ?: continue
            val start = (o["start"] as? JsonPrimitive)?.intOrNull ?: continue
            val end = (o["end"] as? JsonPrimitive)?.intOrNull ?: continue
            if (start < 0 || end <= start || end > codePoints) continue
            val s = text.offsetByCodePoints(0, start)
            val e = text.offsetByCodePoints(0, end)
            val original = (o["original"] as? JsonPrimitive)?.contentOrNull() ?: continue
            if (text.substring(s, e) != original) continue
            val suggestions = (o["suggestions"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
                .filter { it.isNotEmpty() && it != original && it.codePoints().allMatch(ZhText::isHan) }
                .distinct()
            if (suggestions.isEmpty()) continue
            val confidence = ((o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.5).coerceIn(0.0, 1.0)
            val hint = (o["hint"] as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
            out += Suspect(s, e, original, suggestions, confidence, hint)
        }
        out.sortBy { it.start }
        val kept = ArrayList<Suspect>()
        for (s in out) if (kept.lastOrNull()?.let { s.start < it.end } != true) kept += s
        return kept.take(MAX_SUSPECTS)
    }

    /** Models wrap JSON in prose or code fences now and then; take the outermost object. */
    private fun extractJson(answer: String): String {
        val from = answer.indexOf('{')
        val to = answer.lastIndexOf('}')
        return if (from >= 0 && to > from) answer.substring(from, to + 1) else answer
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
}
