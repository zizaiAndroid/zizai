/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.AudioContainer
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderCapabilities
import dev.patrickgold.florisboard.dictate.provider.ProviderPreset
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.lib.devtools.flogError
import io.zizai.ZizaiFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Zizai AI (ADR-9): the AI provider that needs no key. Requests go to zizai-server, which checks the
 * device's free allowance or Zizai Pro and passes them to Groq.
 *
 * To the rest of the app it is an ordinary OpenAI-compatible provider whose "API key" is this device's
 * token. The one thing it does that other providers don't is fetch that token itself, on first use:
 * [ready] runs before every AI card and installs the device account when there is none yet.
 */
object ZizaiAi {
    const val PROVIDER_ID = "zizai"

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installing = Mutex()
    private lateinit var appContext: Context

    /** The provider, built here rather than in upstream's registry so its address can differ per build. */
    val preset: ProviderPreset by lazy {
        ProviderPreset(
            id = PROVIDER_ID,
            displayName = "Zizai AI",
            baseUrl = BuildConfig.ZIZAI_AI_SERVER,
            capabilities = ProviderCapabilities(chat = true, transcription = true),
            supportsDynamicModels = false,
            apiKeyUrl = null,
            defaultChatModel = "zizai-ai",
            defaultTranscriptionModel = "zizai-ai",
            // What Groq's transcription endpoint documents; anything else is converted before upload.
            acceptedAudioContainers = setOf(
                AudioContainer.WAV, AudioContainer.MP3, AudioContainer.M4A, AudioContainer.OGG, AudioContainer.FLAC,
            ),
        )
    }

    val enabled: Boolean get() = ZizaiFlags.zizaiAi && BuildConfig.ZIZAI_AI_SERVER.isNotBlank()

    /** Called from `FlorisApplication.onCreate`: registers the provider and fetches a token if one is needed. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!enabled) return
        ProviderRegistry.extraPresets = listOf(preset)
        scope.launch {
            runCatching {
                val inUse = prefs.dictate.rewordingProviderId.get() == PROVIDER_ID ||
                    prefs.dictate.transcriptionProviderId.get() == PROVIDER_ID
                if (inUse && account().apiKey.isBlank()) install()
            }.onFailure { flogError { "Zizai AI: install at start failed: ${it.message}" } }
        }
    }

    fun account(): ProviderAccount = prefs.dictate.providerAccounts.get().getOrEmpty(PROVIDER_ID)

    /**
     * Returns [account] ready to use: unchanged unless it is Zizai AI without a token, in which case the
     * device account is installed first. Runs before every AI card (`DictateController.requestRewordRaw`).
     */
    suspend fun ready(account: ProviderAccount): ProviderAccount {
        if (account.providerId != PROVIDER_ID || account.apiKey.isNotBlank() || !enabled) return account
        return install()
    }

    /** Installs this device's account, or returns the stored one if another caller got there first. */
    suspend fun install(): ProviderAccount = installing.withLock {
        account().takeIf { it.apiKey.isNotBlank() }?.let { return@withLock it }
        val nonce = ZizaiAiApi.nonce()
        val integrityToken = integrityToken(nonce)
        val installed = ZizaiAiApi.install(nonce, integrityToken, deviceHash())
        store { it.copy(apiKey = installed.token, walletId = installed.accountId) }
        ZizaiPro.update(installed.status)
        account()
    }

    /** Forgets the token after the server said it is not valid any more; the next request installs again. */
    suspend fun forgetToken() {
        store { it.copy(apiKey = "", walletId = "") }
    }

    /** Makes Zizai AI the provider for AI cards. */
    suspend fun useForAiCards() {
        prefs.dictate.rewordingProviderId.set(PROVIDER_ID)
    }

    fun isSelectedForAiCards(): Boolean = prefs.dictate.rewordingProviderId.get() == PROVIDER_ID

    /** The free allowance is used up: the AI card offers Zizai Pro. */
    fun isQuotaExhausted(e: DictateApiException): Boolean =
        enabled && (e.code == ZizaiAiApi.ErrorCode.QUOTA_EXHAUSTED || e.code == ZizaiAiApi.ErrorCode.PRO_REQUIRED)

    /** The short error text for a Zizai AI refusal, or null for anything else. */
    fun outOfCreditMessage(context: Context, e: DictateApiException): String? = when {
        !enabled -> null
        e.code == ZizaiAiApi.ErrorCode.QUOTA_EXHAUSTED -> context.getString(R.string.zizai__pro__quota_chip)
        e.code == ZizaiAiApi.ErrorCode.PRO_REQUIRED -> context.getString(R.string.zizai__pro__pro_required_chip)
        else -> null
    }

    /** A 401 from zizai-server means the account is gone (deleted, or tokens rotated): fetch a new token next time. */
    fun onError(e: DictateApiException) {
        if (!enabled || e.httpStatus != 401 || e.code != ZizaiAiApi.ErrorCode.INVALID_TOKEN) return
        scope.launch { forgetToken() }
    }

    /**
     * Opens the Zizai Pro screen instead of upstream's credit screen when Zizai AI is the provider in use.
     * Hooked into `DictateController.openCloudSettings`, which the "top up" action on an error calls.
     */
    fun openProIfActive(context: Context): Boolean {
        if (!enabled) return false
        val inUse = isSelectedForAiCards() || prefs.dictate.transcriptionProviderId.get() == PROVIDER_ID
        if (!inUse) return false
        openPro(context)
        return true
    }

    fun openPro(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("ui://florisboard/settings/zizai-pro"))
                    .setPackage(context.packageName)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    internal suspend fun store(block: (ProviderAccount) -> ProviderAccount) {
        val accounts = prefs.dictate.providerAccounts.get()
        prefs.dictate.providerAccounts.set(accounts.edit(PROVIDER_ID, block))
    }

    /**
     * A Play Integrity token for [nonce], or "" when Play can't give one (a debug build installed with
     * adb, no Play Store). The server refuses an empty token unless it runs in development mode.
     */
    private suspend fun integrityToken(nonce: String): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val request = IntegrityTokenRequest.builder().setNonce(nonce).apply {
                if (BuildConfig.ZIZAI_INTEGRITY_PROJECT != 0L) setCloudProjectNumber(BuildConfig.ZIZAI_INTEGRITY_PROJECT)
            }.build()
            runCatching {
                IntegrityManagerFactory.create(appContext).requestIntegrityToken(request)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.token()) }
                    .addOnFailureListener {
                        flogError { "Zizai AI: no Play Integrity token: ${it.message}" }
                        if (cont.isActive) cont.resume("")
                    }
            }.onFailure { if (cont.isActive) cont.resume("") }
        }
    }

    /**
     * SHA-256 of the app-scoped Android ID. That ID is unique per app signing key, user and device, and
     * survives a reinstall, which is what keeps the free allowance per device. Only the hash leaves.
     */
    @SuppressLint("HardwareIds")
    private fun deviceHash(): String {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest("zizai:$id".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
