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
import io.zizai.han.lexicon.LexiconReader
import io.zizai.zhtext.ZhConverter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The bundled Chinese data, memory-mapped straight from the APK (assets are stored uncompressed, see the
 * `noCompress` hook in app/build.gradle.kts). Mapping takes a few milliseconds and the pages load on use.
 */
object HanAssets {
    @Volatile private var lexicon: LexiconReader? = null
    @Volatile private var converter: ZhConverter? = null

    fun lexicon(context: Context): LexiconReader =
        lexicon ?: synchronized(this) { lexicon ?: LexiconReader(map(context, "han/han-core.bin")).also { lexicon = it } }

    fun converter(context: Context): ZhConverter =
        converter ?: synchronized(this) { converter ?: ZhConverter.open(map(context, "han/zh-convert.bin")).also { converter = it } }

    private fun map(context: Context, path: String): ByteBuffer {
        context.assets.openFd(path).use { fd ->
            // The mapping stays valid after the descriptor is closed.
            return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
        }
    }
}
