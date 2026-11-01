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
import android.content.res.AssetManager
import java.io.File

/**
 * SenseVoice Small, shipped inside the app (the `zizai-models` asset pack) so dictation works offline
 * from the first launch. sherpa-onnx reads it straight from the APK's assets, so it takes no extra
 * storage. Upstream's loader thinks in model directories, so this answers its questions by directory:
 * a model file that isn't on disk but is in the assets is read from there. A download of the same model
 * (the picker still offers it) lands on disk and takes precedence.
 */
object BundledModel {
    const val ID = "sense-voice-small"
    private const val DIR = "models/$ID"

    @Volatile private var assets: AssetManager? = null
    @Volatile private var files: Set<String> = emptySet()

    /** From `FlorisApplication.onCreate`. */
    fun init(context: Context) {
        val am = context.applicationContext.assets
        files = runCatching { am.list(DIR)?.toSet() }.getOrNull().orEmpty()
        assets = am
    }

    /** Whether [modelId] is the bundled model and the build carries it. */
    fun isBundled(modelId: String): Boolean = modelId == ID && files.isNotEmpty()

    /** The asset path for [name] in [modelDir], when it is read from the APK instead of the disk. */
    fun assetPath(modelDir: File, name: String): String? =
        if (modelDir.name == ID && name in files && !File(modelDir, name).exists()) "$DIR/$name" else null

    fun assetPath(file: File): String? = file.parentFile?.let { assetPath(it, file.name) }

    /** The asset manager sherpa-onnx needs to open [file], or null when it is on disk. */
    fun assetManagerFor(file: File): AssetManager? = if (assetPath(file) != null) assets else null
}
