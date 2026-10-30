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

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.dictateProxyConfig
import dev.patrickgold.florisboard.dictate.provider.ChatMessage
import dev.patrickgold.florisboard.dictate.provider.ChatRequest
import dev.patrickgold.florisboard.dictate.provider.ChatRole
import dev.patrickgold.florisboard.dictate.provider.OpenAiCompatibleClient
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import io.zizai.ZizaiPrefs
import io.zizai.zhtext.Proofreading
import io.zizai.zhtext.Suspect
import io.zizai.zhtext.ZhText

/**
 * LLM homophone proofreading (docs/ARCHITECTURE.md §6.7, P2-08). Off by default and with no default
 * model: it runs only once the user picked one of their configured providers and a model. The model
 * reports suspects; nothing in the text changes unless the user confirms a suggestion.
 */
object Proofreader {
    /** A configured provider that can chat, with the model it would use. */
    data class Choice(val providerId: String, val label: String, val model: String)

    /** The user's providers that can chat and have a key (or need none), each with its chat models. */
    fun choices(): List<Choice> {
        val prefs by FlorisPreferenceStore
        val accounts = prefs.dictate.providerAccounts.get().accounts.values
        return accounts.flatMap { account ->
            val preset = when {
                account.isCustom -> ProviderRegistry.custom(account.customBaseUrl, realtime = account.customRealtime)
                else -> ProviderRegistry.byId(account.providerId)
            } ?: return@flatMap emptyList()
            if (!preset.capabilities.chat || (account.apiKey.isBlank() && !account.isCustom)) return@flatMap emptyList()
            val label = account.displayName.ifBlank { preset.displayName }
            (listOf(account.chatModel) + preset.curatedChatModels).filter { it.isNotBlank() }.distinct()
                .map { Choice(account.providerId, label, it) }
        }.sortedWith(compareBy({ it.label }, { it.model }))
    }

    /** Whether proofreading should run on [text]: switched on, a model chosen, and the text has Chinese. */
    fun applies(prefs: ZizaiPrefs, text: String): Boolean =
        prefs.proofreadEnabled && prefs.proofreadModel.isNotBlank() && prefs.proofreadProvider.isNotBlank() &&
            text.codePoints().anyMatch(ZhText::isHan)

    /** Asks the chosen model about [text]; validated suspects, or empty on any failure. */
    suspend fun check(prefs: ZizaiPrefs, text: String): List<Suspect> = runCatching {
        val app by FlorisPreferenceStore
        val account = app.dictate.providerAccounts.get().getOrEmpty(prefs.proofreadProvider)
        val preset = when {
            account.isCustom -> ProviderRegistry.custom(account.customBaseUrl, realtime = account.customRealtime)
            else -> ProviderRegistry.byId(account.providerId)
        } ?: return emptyList()
        val client = OpenAiCompatibleClient.from(
            preset,
            account.apiKey,
            baseUrlOverride = if (account.isCustom || preset.allowsCustomBaseUrl) account.customBaseUrl.takeIf { it.isNotBlank() } else null,
            proxy = app.dictate.dictateProxyConfig(),
            trustUserCerts = account.trustUserCerts,
            timeoutSeconds = app.dictate.requestTimeout.get().toLong(),
        )
        val answer = client.complete(
            ChatRequest(
                model = prefs.proofreadModel,
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, Proofreading.SYSTEM_PROMPT),
                    ChatMessage(ChatRole.USER, Proofreading.userPrompt(text)),
                ),
                temperature = 0.0,
                reasoningEffort = preset.reasoningEffortWhenOff(prefs.proofreadModel),
            ),
        ).text
        Proofreading.parse(text, answer)
    }.getOrDefault(emptyList())
}
