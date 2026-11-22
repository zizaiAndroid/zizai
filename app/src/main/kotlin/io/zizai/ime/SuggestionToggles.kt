/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ime

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.compose.vectorResource
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.lib.compose.stringRes

/**
 * Smartbar buttons that switch word completions, autocorrect, auto punctuation and the floating mic on and off without a trip
 * through Typing settings. They flip the same two settings the Typing screen has: completions are
 * `suggestion.enabled`, and autocorrect is `suggestion.autoCorrect`, which decides only whether
 * Space swaps a fix in; fixes stay in the strip either way.
 *
 * Autocorrect works through the completions pipeline, so its button shows "on" only when both are
 * on, and turning it on turns completions on too. Otherwise the button could read "on" and do nothing.
 *
 * The icon shows the state. On the first start the buttons go into the ⋯ grid next to incognito,
 * which is the same kind of switch. From there the actions editor can move them into the bar or hide
 * them, and a hidden button stays hidden.
 */
object SuggestionToggles {
    /** Clear of upstream's codes, next to [io.zizai.dictate.ProviderActions.CODE]. */
    const val CODE_COMPLETIONS = -9302
    const val CODE_AUTOCORRECT = -9303
    const val CODE_PUNCTUATION = -9304
    const val CODE_FLOATING_MIC = -9305

    val COMPLETIONS: QuickAction =
        QuickAction.InsertKey(TextKeyData(type = KeyType.SYSTEM_GUI, code = CODE_COMPLETIONS, label = "zizai_toggle_completions"))
    val AUTOCORRECT: QuickAction =
        QuickAction.InsertKey(TextKeyData(type = KeyType.SYSTEM_GUI, code = CODE_AUTOCORRECT, label = "zizai_toggle_autocorrect"))
    val PUNCTUATION: QuickAction =
        QuickAction.InsertKey(TextKeyData(type = KeyType.SYSTEM_GUI, code = CODE_PUNCTUATION, label = "zizai_toggle_punctuation"))
    /** Floats the mic over the screen or puts it back in the Smartbar ([FloatingMic]). */
    val FLOATING_MIC: QuickAction =
        QuickAction.InsertKey(TextKeyData(type = KeyType.SYSTEM_GUI, code = CODE_FLOATING_MIC, label = "zizai_toggle_floating_mic"))

    /**
     * The three automatic punctuation settings the punctuation switch handles together: double-tap
     * Space for a full stop, a space after a punctuation mark, and dropping a space typed before one.
     * The switch reads "on" while any of them is on. Off remembers the mix, and on puts it back, or
     * the defaults (only the double-tap) when there is nothing to put back.
     */
    data class Punctuation(val doubleSpacePeriod: Boolean, val autoSpace: Boolean, val tighten: Boolean) {
        val anyOn get() = doubleSpacePeriod || autoSpace || tighten
        val mask get() = (if (doubleSpacePeriod) 1 else 0) or (if (autoSpace) 2 else 0) or (if (tighten) 4 else 0)

        companion object {
            val OFF = Punctuation(false, false, false)
            val DEFAULTS = Punctuation(doubleSpacePeriod = true, autoSpace = false, tighten = false)
            fun of(mask: Int) = Punctuation(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0)
        }
    }

    /** The settings after a tap on the punctuation switch, and what to remember (-1 for nothing). */
    fun afterPunctuationTap(current: Punctuation, remembered: Int): Pair<Punctuation, Int> =
        if (current.anyOn) {
            Punctuation.OFF to current.mask
        } else {
            val restored = Punctuation.of(remembered).takeIf { remembered > 0 && it.anyOn } ?: Punctuation.DEFAULTS
            restored to -1
        }

    data class State(val completions: Boolean, val autoCorrect: Boolean) {
        /** What the autocorrect button shows: Space only fixes words when completions are running. */
        val autoCorrectEffective get() = completions && autoCorrect
    }

    /** The settings after a tap on the button with [code]. */
    fun afterTap(state: State, code: Int): State = when (code) {
        CODE_COMPLETIONS -> state.copy(completions = !state.completions)
        CODE_AUTOCORRECT -> if (state.autoCorrectEffective) {
            state.copy(autoCorrect = false)
        } else {
            State(completions = true, autoCorrect = true)
        }
        else -> state
    }

    /**
     * [arrangement] with any button it lacks added after incognito and the other switches in the bar's
     * list, or at its end.
     * A button already anywhere in it, even hidden, is left where it is.
     */
    fun withButtons(arrangement: QuickActionArrangement): QuickActionArrangement {
        val missing = listOf(COMPLETIONS, AUTOCORRECT, PUNCTUATION, FLOATING_MIC).filter { it !in arrangement }
        if (missing.isEmpty()) return arrangement
        val dynamic = arrangement.dynamicActions.toMutableList()
        // After the last of incognito and the switches already there, so a newer switch joins the others.
        val anchor = dynamic.indexOfLast {
            val code = (it as? QuickAction.InsertKey)?.data?.code
            code == KeyCode.TOGGLE_INCOGNITO_MODE || (code != null && isToggle(code))
        }
        if (anchor >= 0) dynamic.addAll(anchor + 1, missing) else dynamic.addAll(missing)
        return arrangement.copy(dynamicActions = dynamic)
    }

    private fun isToggle(code: Int) = code == CODE_COMPLETIONS || code == CODE_AUTOCORRECT || code == CODE_PUNCTUATION || code == CODE_FLOATING_MIC

    private fun codeOf(action: QuickAction): Int? =
        (action as? QuickAction.InsertKey)?.data?.code?.takeIf(::isToggle)

    private fun current() = State(prefs.suggestion.enabled.get(), prefs.suggestion.autoCorrect.get())

    private fun punctuation() = Punctuation(
        prefs.correction.doubleSpacePeriod.get(),
        prefs.correction.autoSpacePunctuation.get(),
        prefs.correction.tightenPunctuationSpacing.get(),
    )

    private fun onPunctuationKey(context: Context) {
        val zizaiPrefs = io.zizai.ZizaiPrefs.get(context)
        val (after, remember) = afterPunctuationTap(punctuation(), zizaiPrefs.punctuationSwitchMemory)
        zizaiPrefs.punctuationSwitchMemory = remember
        scope.launch {
            prefs.correction.doubleSpacePeriod.set(after.doubleSpacePeriod)
            prefs.correction.autoSpacePunctuation.set(after.autoSpace)
            prefs.correction.tightenPunctuationSpacing.set(after.tighten)
        }
        val message = if (after.anyOn) R.string.zizai__toggle_punctuation__on else R.string.zizai__toggle_punctuation__off
        runCatching { context.showShortToastSync(message) }
    }

    /** From the key-up path: flip the setting on this button. */
    fun onKey(data: KeyData, context: Context): Boolean {
        if (!isToggle(data.code)) return false
        if (data.code == CODE_FLOATING_MIC) {
            val on = !FloatingMic.isEnabled(context)
            FloatingMic.setEnabled(context, on)
            val message = if (on) R.string.zizai__toggle_floating_mic__on else R.string.zizai__floating_mic__docked
            runCatching { context.showShortToastSync(message) }
            return true
        }
        if (data.code == CODE_PUNCTUATION) {
            onPunctuationKey(context)
            return true
        }
        val before = current()
        val after = afterTap(before, data.code)
        scope.launch {
            if (after.completions != before.completions) prefs.suggestion.enabled.set(after.completions)
            if (after.autoCorrect != before.autoCorrect) prefs.suggestion.autoCorrect.set(after.autoCorrect)
        }
        val message = when (data.code) {
            CODE_COMPLETIONS -> if (after.completions) R.string.zizai__toggle_completions__on else R.string.zizai__toggle_completions__off
            else -> if (after.autoCorrectEffective) R.string.zizai__toggle_autocorrect__on else R.string.zizai__toggle_autocorrect__off
        }
        runCatching { context.showShortToastSync(message) }
        return true
    }

    /** [context] draws the slashed spellcheck, which Material does not have. The wand would read as AI prompts. */
    fun icon(data: KeyData, context: Context?): ImageVector? {
        val state = current()
        return when (data.code) {
            CODE_COMPLETIONS -> if (state.completions) Icons.Default.Subtitles else Icons.Default.SubtitlesOff
            CODE_AUTOCORRECT -> if (state.autoCorrectEffective) Icons.Default.Spellcheck else context?.vectorResource(R.drawable.ic_zizai_spellcheck_off)
            CODE_PUNCTUATION -> if (punctuation().anyOn) Icons.Default.FormatQuote else context?.vectorResource(R.drawable.ic_zizai_punctuation_off)
            CODE_FLOATING_MIC -> if (context != null && FloatingMic.isEnabled(context)) Icons.Default.PictureInPictureAlt else context?.vectorResource(R.drawable.ic_zizai_floating_mic_off)
            else -> null
        }
    }

    /** Read by the button's icon so it is redrawn when either setting changes, from here or elsewhere. */
    @Composable
    fun observe(): Any {
        val completions by prefs.suggestion.enabled.collectAsState()
        val autoCorrect by prefs.suggestion.autoCorrect.collectAsState()
        val doubleSpace by prefs.correction.doubleSpacePeriod.collectAsState()
        val autoSpace by prefs.correction.autoSpacePunctuation.collectAsState()
        val tighten by prefs.correction.tightenPunctuationSpacing.collectAsState()
        val floating = FloatingMic.observeEnabled()
        return Triple(State(completions, autoCorrect), Punctuation(doubleSpace, autoSpace, tighten), floating)
    }

    @Composable
    fun displayName(action: QuickAction): String? = when (codeOf(action)) {
        CODE_COMPLETIONS -> stringRes(R.string.zizai__toggle_completions)
        CODE_AUTOCORRECT -> stringRes(R.string.zizai__toggle_autocorrect)
        CODE_PUNCTUATION -> stringRes(R.string.zizai__toggle_punctuation)
        CODE_FLOATING_MIC -> stringRes(R.string.zizai__toggle_floating_mic)
        else -> null
    }

    @Composable
    fun tooltip(action: QuickAction): String? = when (codeOf(action)) {
        CODE_COMPLETIONS -> stringRes(R.string.zizai__toggle_completions__tooltip)
        CODE_AUTOCORRECT -> stringRes(R.string.zizai__toggle_autocorrect__tooltip)
        CODE_PUNCTUATION -> stringRes(R.string.zizai__toggle_punctuation__tooltip)
        CODE_FLOATING_MIC -> stringRes(R.string.zizai__toggle_floating_mic__tooltip)
        else -> null
    }

    /** Adds the buttons to the user's arrangement once. Call only after the settings have loaded. */
    suspend fun sync() {
        val current = prefs.smartbar.actionArrangement.get()
        val next = withButtons(current)
        if (next != current) prefs.smartbar.actionArrangement.set(next)
    }

    private val prefs by FlorisPreferenceStore

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
}
