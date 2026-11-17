/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.app

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictatePromptsLayout
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * "Show prompt row" on the Smartbar screen: the AI prompt row above the suggestions, on or off.
 *
 * Not a preference of its own. Off is upstream's PANEL prompt layout, where the prompts move behind the
 * magic-wand button, so the keyboard's height, the quick actions and the panels all follow it as they
 * already do. The same choice stays under Dictate → Rewording → Prompt layout; this is the place people
 * look for it.
 */
@Composable
fun PromptRowSwitch() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val layout by prefs.dictate.promptsLayout.collectAsState()
    val rewording by prefs.dictate.rewordingEnabled.collectAsState()
    val shown = layout == DictatePromptsLayout.ROW
    fun set(show: Boolean) {
        scope.launch {
            prefs.dictate.promptsLayout.set(if (show) DictatePromptsLayout.ROW else DictatePromptsLayout.PANEL)
        }
    }
    Preference(
        title = stringRes(R.string.zizai__prompt_row__title),
        summary = stringRes(R.string.zizai__prompt_row__summary),
        onClick = { set(!shown) },
        trailing = { Switch(checked = shown && rewording, onCheckedChange = ::set, enabled = rewording) },
        enabledIf = { prefs.dictate.rewordingEnabled isEqualTo true },
    )
}
