/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.dictate

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.florisboard.lib.compose.stringRes

/**
 * Shows the built-in prompt cards ("Fix Grammar", "Translate to English", …) in the keyboard's current
 * language. The starter prompts are written to the database once, in the language the phone had at
 * that moment, so switching the keyboard to 中 still showed English cards. A stored name that is any
 * translation of a starter name is shown as that starter in the current language; a name the user
 * typed or edited is shown as written.
 */
object PromptNames {
    private val STARTERS = intArrayOf(
        R.string.dictate__example_prompt_fix_grammar_name,
        R.string.dictate__example_prompt_improve_name,
        R.string.dictate__example_prompt_formal_name,
        R.string.dictate__example_prompt_friendly_name,
        R.string.dictate__example_prompt_shorter_name,
        R.string.dictate__example_prompt_translate_name,
        R.string.dictate__example_prompt_quote_name,
        R.string.dictate__example_prompt_funfact_name,
        R.string.dictate__example_prompt_shrug_name,
        R.string.dictate__example_prompt_signoff_name,
    )

    /** Every translation of every starter name → its string id; built once, off the main thread. */
    private val known = mutableStateOf<Map<String, Int>?>(null)
    private val building = AtomicBoolean(false)

    @Composable
    fun display(name: String?): String {
        val stored = name.orEmpty()
        val names = known.value
        if (names == null) {
            val context = LocalContext.current.applicationContext
            if (building.compareAndSet(false, true)) thread(name = "ZizaiPromptNames") { known.value = translations(context, STARTERS) }
            return stored
        }
        val id = names[stored.trim()] ?: return stored
        return stringRes(id)
    }

    /** Every translation the app ships of each string in [ids] → that string's id. Slow: call off the main thread. */
    fun translations(context: Context, ids: IntArray): Map<String, Int> {
        val names = HashMap<String, Int>()
        val tags = context.assets.locales.toList() + ""
        for (tag in tags) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val res = runCatching { context.createConfigurationContext(config).resources }.getOrNull() ?: continue
            ids.forEach { id -> names.putIfAbsent(res.getString(id).trim(), id) }
        }
        return names
    }
}
