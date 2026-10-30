/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

/**
 * Qwen3-ASR-Flash on Alibaba Cloud Model Studio (字在 Zizai, docs/IMPLEMENTATION_PLAN.md P2-06;
 * [TranscriptionApi.QWEN_CHAT_AUDIO]).
 *
 * Model Studio's OpenAI-compatible mode takes speech on `chat/completions`, not `audio/transcriptions`:
 * one `input_audio` part holding a base64 data URL (10 MB after encoding, five minutes of audio), an
 * optional system message as context (the glossary of custom words and the user's own phrases), and
 * `asr_options` with the language and inverse text normalization. The transcript comes back in
 * `choices[0].message.content`. The presets accept WAV and MP3 only, so upstream hands this a WAV.
 *
 * API reference (read 2026-10-30): https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference
 */
internal class QwenAsrClient(
    private val config: ProviderConfig,
    private val execute: suspend (Request, (Int) -> Unit) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun transcribe(request: TranscriptionRequest, onRetry: (Int) -> Unit): TranscriptionResult {
        val container = AudioContainer.of(request.audioFile)
        val dataUrl = withContext(Dispatchers.IO) { dataUrl(request.audioFile, container) }
        if (dataUrl.length > MAX_DATA_URL_CHARS) {
            throw DictateApiException(
                DictateApiException.Kind.CONTENT_SIZE_LIMIT,
                "Recording too long for Qwen3-ASR (more than 10 MB once encoded)",
            )
        }
        val language = languageCode(request.language)
        val messages = buildList {
            request.prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { context ->
                add(Message("system", listOf(Part(text = context))))
            }
            add(Message("user", listOf(Part(type = "input_audio", inputAudio = InputAudio(dataUrl)))))
        }
        val dto = RequestDto(
            model = request.model,
            messages = messages,
            stream = false,
            asrOptions = AsrOptions(language = language, enableItn = language in ITN_LANGUAGES),
        )
        val httpRequest = Request.Builder()
            .url(config.normalizedBaseUrl + "chat/completions")
            .headers(headers())
            .post(json.encodeToString(RequestDto.serializer(), dto).toRequestBody(JSON))
            .build()
        val body = execute(httpRequest, onRetry)
        val response = json.decodeFromString(ResponseDto.serializer(), body)
        val text = response.choices.firstOrNull()?.message?.content
            ?: throw DictateApiException(DictateApiException.Kind.UNKNOWN, "Empty response from Qwen3-ASR")
        return TranscriptionResult(text.trim())
    }

    private fun headers(): Headers {
        val builder = Headers.Builder()
        if (config.apiKey.isNotBlank()) builder.add("Authorization", "Bearer ${config.apiKey}")
        config.extraHeaders.forEach { (k, v) -> builder.add(k, v) }
        return builder.build()
    }

    @Serializable
    private data class RequestDto(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean,
        @SerialName("asr_options") val asrOptions: AsrOptions,
    )

    @Serializable
    private data class Message(val role: String, val content: List<Part>)

    @Serializable
    private data class Part(
        val type: String = "text",
        val text: String? = null,
        @SerialName("input_audio") val inputAudio: InputAudio? = null,
    )

    @Serializable
    private data class InputAudio(val data: String)

    @Serializable
    private data class AsrOptions(
        val language: String? = null,
        @SerialName("enable_itn") val enableItn: Boolean = false,
    )

    @Serializable
    private data class ResponseDto(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ResponseMessage? = null)

    @Serializable
    private data class ResponseMessage(val content: String? = null)

    companion object {
        /** The 10 MB input limit, which the base64 data URL counts against. */
        const val MAX_DATA_URL_CHARS = 10 * 1024 * 1024

        /** Inverse text normalization (一百二十 → 120) works for these only. */
        private val ITN_LANGUAGES = setOf("zh", "en")

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Our language choice as Qwen's code: `zh-CN`, `zh-TW` → `zh` (which also covers Sichuanese, Minnan
         * and Wu), `yue-HK` → `yue`, `en-US` → `en`; auto-detect → none, which lets the model decide.
         */
        fun languageCode(language: String?): String? {
            val code = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
            return code?.takeIf { it.isNotEmpty() && it != "detect" }
        }

        fun dataUrl(file: File, container: AudioContainer): String {
            val mime = if (container == AudioContainer.UNKNOWN) "audio/wav" else container.mimeType
            return "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }
    }
}
