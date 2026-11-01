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

import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * zizai-server's account endpoints: install, account status, subscription, deletion. The AI requests
 * themselves don't come through here: the server speaks OpenAI's format for those, so they go through
 * upstream's `OpenAiCompatibleClient` with the device token as the key.
 */
object ZizaiAiApi {
    /** `error.code` values the app acts on; the server's `ErrorCode` in `zizai-server/src/http.ts`. */
    object ErrorCode {
        const val INVALID_TOKEN = "invalid_token"
        const val QUOTA_EXHAUSTED = "quota_exhausted"
        const val PRO_REQUIRED = "pro_required"
        const val DEVICE_LIMIT = "device_limit"
        const val INTEGRITY_FAILED = "integrity_failed"
    }

    private val base get() = BuildConfig.ZIZAI_AI_SERVER.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()
    }

    suspend fun nonce(): String = call<NonceReply>(Request.Builder().url("$base/install/nonce").get().build()).nonce

    suspend fun install(nonce: String, integrityToken: String, deviceHash: String): Installed {
        val body = json.encodeToString(InstallRequest(nonce, integrityToken.ifBlank { null }, deviceHash))
        val reply = call<AccountReply>(Request.Builder().url("$base/install").post(body.toRequestBody(jsonMedia)).build())
        return Installed(reply.accountId, reply.token.orEmpty(), reply.status())
    }

    suspend fun account(token: String): AccountStatus =
        call<AccountReply>(authed("$base/account", token).get().build()).status()

    suspend fun attachSubscription(token: String, purchaseToken: String): AccountStatus {
        val body = json.encodeToString(SubscriptionRequest(purchaseToken))
        return call<AccountReply>(authed("$base/subscription", token).post(body.toRequestBody(jsonMedia)).build()).status()
    }

    suspend fun deleteAccount(token: String) {
        call<DeletedReply>(authed("$base/account/delete", token).post("{}".toRequestBody(jsonMedia)).build())
    }

    data class Installed(val accountId: String, val token: String, val status: AccountStatus)

    /** What the server says about this device's account. */
    data class AccountStatus(
        val accountId: String,
        val pro: Boolean,
        /** Epoch ms, 0 when not Pro. */
        val proUntil: Long,
        val actions: Int,
        val actionsLimit: Int,
        val audioSeconds: Int,
        val audioSecondsLimit: Int,
        val slowed: Boolean,
        val resetsAt: Long,
    )

    class ZizaiAiException(val status: Int, val code: String, message: String) : Exception(message)

    private fun authed(url: String, token: String) = Request.Builder().url(url).header("authorization", "Bearer $token")

    /** On the IO dispatcher: reading the body blocks, and callers are often on the main thread. */
    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw ZizaiAiException(0, "unreachable", e.message ?: "Network error")
        }
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) {
            val error = runCatching { json.decodeFromString<ErrorEnvelope>(body).error }.getOrNull()
            throw ZizaiAiException(response.code, error?.code.orEmpty(), error?.message ?: "HTTP ${response.code}")
        }
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw ZizaiAiException(response.code, "bad_response", e.message ?: "Unreadable reply")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    @Serializable
    private data class NonceReply(val nonce: String)

    @Serializable
    private data class InstallRequest(val nonce: String, val integrityToken: String?, val deviceHash: String)

    @Serializable
    private data class SubscriptionRequest(val purchaseToken: String)

    @Serializable
    private data class DeletedReply(val deleted: Boolean = false)

    @Serializable
    private data class Usage(
        val actions: Int = 0,
        @SerialName("actions_limit") val actionsLimit: Int = 0,
        @SerialName("audio_seconds") val audioSeconds: Int = 0,
        @SerialName("audio_seconds_limit") val audioSecondsLimit: Int = 0,
        val slowed: Boolean = false,
        @SerialName("resets_at") val resetsAt: Long = 0,
    )

    @Serializable
    private data class AccountReply(
        @SerialName("account_id") val accountId: String = "",
        val token: String? = null,
        val plan: String = "free",
        @SerialName("pro_until") val proUntil: Long? = null,
        val usage: Usage = Usage(),
    ) {
        fun status() = AccountStatus(
            accountId = accountId,
            pro = plan == "pro",
            proUntil = proUntil ?: 0L,
            actions = usage.actions,
            actionsLimit = usage.actionsLimit,
            audioSeconds = usage.audioSeconds,
            audioSecondsLimit = usage.audioSecondsLimit,
            slowed = usage.slowed,
            resetsAt = usage.resetsAt,
        )
    }

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val message: String? = null, val code: String? = null)
}
