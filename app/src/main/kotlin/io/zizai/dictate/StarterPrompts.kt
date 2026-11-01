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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.ui.DictatePromptChip
import kotlin.concurrent.thread
import org.florisboard.lib.compose.stringRes

/**
 * Zizai starts with three AI prompt cards (Fix Grammar, Improve Writing, Translate to English) and an
 * "Add your prompt" card, instead of upstream's ten. Installs that already have the seven retired
 * starters lose them once, unless the user changed the card's name or instructions.
 */
object StarterPrompts {
    /** `editPromptId` that opens the prompts screen on a new, empty prompt. */
    const val NEW_PROMPT = -2

    private const val PREFS = "zizai_starter_prompts"
    private const val RETIRED_KEY = "retired_v1"

    private val RETIRED_NAMES = intArrayOf(
        R.string.dictate__example_prompt_formal_name,
        R.string.dictate__example_prompt_friendly_name,
        R.string.dictate__example_prompt_shorter_name,
        R.string.dictate__example_prompt_quote_name,
        R.string.dictate__example_prompt_funfact_name,
        R.string.dictate__example_prompt_shrug_name,
        R.string.dictate__example_prompt_signoff_name,
    )
    private val RETIRED_PROMPTS = intArrayOf(
        R.string.dictate__example_prompt_formal_prompt,
        R.string.dictate__example_prompt_friendly_prompt,
        R.string.dictate__example_prompt_shorter_prompt,
        R.string.dictate__example_prompt_quote_prompt,
        R.string.dictate__example_prompt_funfact_prompt,
        R.string.dictate__example_prompt_shrug_prompt,
        R.string.dictate__example_prompt_signoff_prompt,
    )

    /** Deletes the retired starters the user never edited; runs once per install, in the background. */
    fun removeRetired(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(RETIRED_KEY, false)) return
        thread(name = "ZizaiStarterPrompts") {
            val names = PromptNames.translations(context, RETIRED_NAMES)
            val prompts = PromptNames.translations(context, RETIRED_PROMPTS)
            val db = PromptsDatabaseHelper.getInstance(context)
            var removed = 0
            for (prompt in db.getAll()) {
                val nameId = names[prompt.name.orEmpty().trim()] ?: continue
                val promptId = prompts[prompt.prompt.orEmpty().trim()] ?: continue
                if (RETIRED_NAMES.indexOf(nameId) != RETIRED_PROMPTS.indexOf(promptId)) continue
                db.delete(prompt.id)
                removed++
            }
            prefs.edit().putBoolean(RETIRED_KEY, true).apply()
            if (removed > 0) DictateController.refreshPrompts(context)
        }
    }

    /** Last card in the prompt row: opens the prompts screen on a new prompt. */
    @Composable
    fun AddCard(tapPadding: PaddingValues) {
        DictatePromptChip(
            icon = Icons.Default.Add,
            text = stringRes(R.string.zizai__prompts__add_card),
            onClick = { FlorisImeService.launchSettings("settings/dictate/prompts?editPromptId=$NEW_PROMPT") },
            modifier = Modifier.padding(horizontal = 1.5.dp).then(PromptCarousel.card(PromptCarousel.ADD)),
            tapPadding = tapPadding,
        )
    }
}
