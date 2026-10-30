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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import kotlin.io.path.createTempFile

/** P2-06: Qwen3-ASR-Flash's wire format against a mock server (upstream's MockWebServer pattern). */
class QwenAsrClientTest : FunSpec({
    fun wav(bytes: Int = 64) = createTempFile(suffix = ".wav").toFile().apply {
        writeBytes("RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray() + ByteArray(bytes))
    }

    fun client(server: MockWebServer) = OpenAiCompatibleClient(
        ProviderConfig(
            baseUrl = server.url("/compatible-mode/v1/").toString(),
            apiKey = "sk-test",
            transcriptionApi = TranscriptionApi.QWEN_CHAT_AUDIO,
        ),
    )

    val reply = """{"choices":[{"message":{"role":"assistant","content":"  今天天气很好。 ",
        "annotations":[{"type":"audio_info","language":"zh","emotion":"neutral"}]}}],
        "usage":{"seconds":3}}"""

    test("a dictation goes to chat/completions as a WAV data URL with context and asr_options") {
        val audio = wav()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody(reply))
                val result = client(server).transcribe(
                    TranscriptionRequest(audioFile = audio, model = "qwen3-asr-flash", language = "zh-CN", prompt = "张三丰, 微信群"),
                )
                result.text shouldBe "今天天气很好。"

                val recorded = server.takeRequest()
                recorded.method shouldBe "POST"
                recorded.path shouldBe "/compatible-mode/v1/chat/completions"
                recorded.getHeader("Authorization") shouldBe "Bearer sk-test"
                val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                body["model"]!!.jsonPrimitive.content shouldBe "qwen3-asr-flash"
                body["stream"]!!.jsonPrimitive.boolean shouldBe false
                val options = body["asr_options"]!!.jsonObject
                options["language"]!!.jsonPrimitive.content shouldBe "zh"
                options["enable_itn"]!!.jsonPrimitive.boolean shouldBe true

                val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
                messages.map { it["role"]!!.jsonPrimitive.content } shouldBe listOf("system", "user")
                messages[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe "张三丰, 微信群"
                val part = messages[1]["content"]!!.jsonArray.single().jsonObject
                part["type"]!!.jsonPrimitive.content shouldBe "input_audio"
                val data = (part["input_audio"] as JsonObject)["data"]!!.jsonPrimitive.content
                data shouldContain "data:audio/wav;base64,"
                Base64.getDecoder().decode(data.substringAfter(",")).toList() shouldBe audio.readBytes().toList()
            }
        } finally {
            audio.delete()
        }
    }

    test("Cantonese and auto-detect: yue without ITN, and no language at all") {
        val audio = wav()
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(reply))
                server.enqueue(MockResponse().setBody(reply))
                client(server).transcribe(TranscriptionRequest(audio, "qwen3-asr-flash", language = "yue-HK"))
                val yue = server.takeRequest().body.readUtf8()
                yue shouldContain "\"language\":\"yue\""
                yue shouldNotContain "\"enable_itn\":true"
                yue shouldNotContain "\"system\""

                client(server).transcribe(TranscriptionRequest(audio, "qwen3-asr-flash", language = "detect"))
                server.takeRequest().body.readUtf8() shouldNotContain "\"language\""
            }
        } finally {
            audio.delete()
        }
    }

    test("an error answer is reported, not returned as text") {
        val audio = wav()
        try {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setResponseCode(401)
                        .setBody("""{"error":{"message":"Incorrect API key provided.","code":"invalid_api_key"}}"""),
                )
                shouldThrow<DictateApiException> {
                    client(server).transcribe(TranscriptionRequest(audio, "qwen3-asr-flash", language = "zh-CN"))
                }
            }
        } finally {
            audio.delete()
        }
    }

    test("language codes and presets") {
        QwenAsrClient.languageCode("zh-TW") shouldBe "zh"
        QwenAsrClient.languageCode("yue-CN") shouldBe "yue"
        QwenAsrClient.languageCode("en") shouldBe "en"
        QwenAsrClient.languageCode("detect") shouldBe null
        QwenAsrClient.languageCode(null) shouldBe null
        for (preset in listOf(ProviderRegistry.QWEN_SINGAPORE, ProviderRegistry.QWEN_BEIJING)) {
            preset.transcriptionApi shouldBe TranscriptionApi.QWEN_CHAT_AUDIO
            preset.allowsCustomBaseUrl shouldBe true
            preset.acceptedAudioContainers shouldBe setOf(AudioContainer.WAV, AudioContainer.MP3)
            ProviderRegistry.byId(preset.id) shouldBe preset
        }
    }
})
