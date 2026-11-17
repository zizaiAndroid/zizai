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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.ProviderListing
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One Smartbar button per transcription provider: a tap makes it the provider, without the picker
 * sheet in between. The active one carries a mark under its monogram.
 *
 * Each button is an ordinary `InsertKey` action with one shared key code and the provider's id in the
 * key's label, so the actions editor arranges them like any other button and the arrangement stores
 * them with no change to its format. [sync] keeps the set in step with the providers that are set up:
 * a new one arrives among the hidden actions, ready to be dragged into the bar, and a removed one goes.
 */
object ProviderActions {
    /** Shared by every provider button; the provider is in the label. Clear of upstream's codes. */
    const val CODE = -9301

    private const val PREFIX = "zizai_provider:"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun keyData(providerId: String) = TextKeyData(type = KeyType.SYSTEM_GUI, code = CODE, label = PREFIX + providerId)

    fun action(providerId: String): QuickAction = QuickAction.InsertKey(keyData(providerId))

    fun providerIdOf(data: KeyData): String? =
        if (data.code == CODE) data.label.removePrefix(PREFIX).takeIf { it.isNotEmpty() } else null

    private fun providerIdOf(action: QuickAction): String? =
        (action as? QuickAction.InsertKey)?.data?.let(::providerIdOf)

    /** From the key-up path: make the provider on this button the transcription provider. */
    fun onKey(data: KeyData): Boolean {
        val id = providerIdOf(data) ?: return false
        DictateController.setTranscriptionProvider(id)
        return true
    }

    /** The button's face: a phone for the on-device model, otherwise no icon, so [label] is drawn. */
    fun icon(data: KeyData): ImageVector? = if (providerIdOf(data) == "local") Icons.Default.PhoneAndroid else null

    /** A short monogram, so several providers in the bar can be told apart at a glance. */
    fun label(data: KeyData): String? {
        val id = providerIdOf(data) ?: return null
        MONOGRAMS[id]?.let { return it }
        if (id.startsWith("qwen")) return "Qw"
        val name = ProviderListing.displayNameOf(id, prefs.dictate.providerAccounts.get())
        val letters = name.filter { it.isLetterOrDigit() }
        return letters.take(2).replaceFirstChar { it.uppercaseChar() }.ifEmpty { "?" }
    }

    /** The name for the editor's tile and the screen reader, or null for any other action. */
    @Composable
    fun displayName(action: QuickAction): String? {
        val id = providerIdOf(action) ?: return null
        return ProviderListing.displayNameOf(id, prefs.dictate.providerAccounts.get())
    }

    @Composable
    fun tooltip(action: QuickAction): String? {
        val name = displayName(action) ?: return null
        return LocalContext.current.getString(R.string.zizai__provider_action__tooltip, name)
    }

    /** The mark under the active provider's button. Draws nothing on any other button. */
    @Composable
    fun BoxScope.SelectedMark(action: QuickAction) {
        val id = providerIdOf(action) ?: return
        val selected by prefs.dictate.transcriptionProviderId.collectAsState()
        if (id != selected) return
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 3.dp)
                .size(width = 14.dp, height = 3.dp)
                .background(Accent, RoundedCornerShape(2.dp)),
        )
    }

    /**
     * Brings the provider buttons in line with what is set up: removes those whose provider is gone and
     * adds the missing ones to the hidden actions. Writes only when something changed.
     */
    fun sync(context: Context) {
        // Before the store has loaded (and upstream's migrations have run), get() answers with defaults,
        // and writing a default arrangement plus the provider buttons would replace the user's own.
        val app = context.applicationContext as? dev.patrickgold.florisboard.FlorisApplication ?: return
        if (!app.preferenceStoreLoaded.value) return
        val choices = runCatching {
            ProviderListing.transcriptionChoices(prefs.dictate.providerAccounts.get(), selectedId = "") {
                LocalModelManager.isInstalled(context, it)
            }
        }.getOrElse { return }
        val wanted = choices.map { action(it.first) }
        val current = prefs.smartbar.actionArrangement.get()
        fun keep(a: QuickAction) = providerIdOf(a) == null || a in wanted
        val pruned = QuickActionArrangement(
            stickyAction = current.stickyAction?.takeIf(::keep),
            dynamicActions = current.dynamicActions.filter(::keep),
            hiddenActions = current.hiddenActions.filter(::keep),
        )
        val missing = wanted.filter { it !in pruned }
        val next = if (missing.isEmpty()) pruned else pruned.copy(hiddenActions = pruned.hiddenActions + missing)
        if (next != current) scope.launch { prefs.smartbar.actionArrangement.set(next) }
    }

    private val prefs by FlorisPreferenceStore

    private val Accent = Color(0xFF0E7C66)

    private val MONOGRAMS = mapOf(
        "zizai" to "字",
        "openai" to "OA",
        "cloud" to "DC",
        "groq" to "Gq",
        "openrouter" to "OR",
        "gemini" to "Gm",
        "anthropic" to "An",
        "together" to "To",
        "deepinfra" to "DI",
        "mistral" to "Mi",
        "soniox" to "So",
        "elevenlabs" to "11",
        "deepgram" to "DG",
        "assemblyai" to "AA",
        "azure" to "Az",
        "xai" to "xA",
        "deepseek" to "DS",
        "siliconflow" to "SF",
        "scaleway" to "Sw",
        "ovhcloud" to "OV",
        "ollama" to "Ol",
    )
}
