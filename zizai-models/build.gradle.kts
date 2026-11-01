/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import java.net.URI
import java.security.MessageDigest
import java.util.Properties

/*
 * The on-device speech model every install starts with: SenseVoice Small (Mandarin, Cantonese, English,
 * Japanese, Korean), so dictation works offline out of the box with nothing to download first.
 *
 * At 239 MB it would put the base APK past Play's 200 MB limit, so it ships as an install-time asset
 * pack: Play installs it with the app, and it reads like any other asset. The files are fetched from
 * Zizai's release mirror at build time, checked against their SHA-256, and never committed.
 */
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("zizai_models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

val zizaiProps = Properties().apply { rootProject.file("zizai.properties").inputStream().use(::load) }
val releasesBase = zizaiProps.getProperty("releasesBase").trim().trimEnd('/')

/** Release file → name in the pack, size and SHA-256 (the same as LocalModelCatalog.SENSE_VOICE_SMALL). */
val senseVoiceFiles = listOf(
    Triple("sense-voice-small-model.int8.onnx", "model.onnx",
        239_233_841L to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    Triple("sense-voice-small-tokens.txt", "tokens.txt",
        315_894L to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
    Triple("silero_vad.onnx", "vad.onnx",
        643_854L to "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
)

val modelDir = layout.projectDirectory.dir("src/main/assets/models/sense-voice-small")

val fetchSenseVoice by tasks.registering {
    description = "Downloads SenseVoice Small into the asset pack (once; verified by SHA-256)."
    outputs.dir(modelDir)
    doLast {
        val dir = modelDir.asFile.apply { mkdirs() }
        for ((remote, local, check) in senseVoiceFiles) {
            val (size, sha) = check
            val out = File(dir, local)
            if (out.length() == size) continue
            val tmp = File(dir, "$local.part")
            logger.lifecycle("Downloading $remote (${size / 1_000_000} MB)")
            URI("$releasesBase/whisper-models-v1/$remote").toURL().openStream().use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha) { "$remote: SHA-256 $actual, expected $sha" }
            check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "could not move $tmp" }
        }
    }
}

// Every packaging task of this pack needs the files on disk first.
tasks.configureEach { if (name != fetchSenseVoice.name && !name.startsWith("clean")) dependsOn(fetchSenseVoice) }
