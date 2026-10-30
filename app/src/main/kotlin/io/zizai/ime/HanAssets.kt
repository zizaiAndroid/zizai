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
import android.util.Log
import io.zizai.han.lexicon.LexiconReader
import io.zizai.zhtext.ZhConverter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The Chinese data, memory-mapped: the bundled core straight from the APK (assets are stored uncompressed,
 * see the `noCompress` hook in app/build.gradle.kts), or the downloaded modern pack when it's installed
 * (P1-08). Mapping takes a few milliseconds and the pages load on use.
 */
object HanAssets {
    @Volatile private var lexicon: LexiconReader? = null
    @Volatile private var converter: ZhConverter? = null

    private val _generation = MutableStateFlow(0)

    /** Bumped whenever the lexicon changes, so the keyboard rebuilds its session. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun lexicon(context: Context): LexiconReader =
        lexicon ?: synchronized(this) { lexicon ?: load(context).also { lexicon = it } }

    fun converter(context: Context): ZhConverter =
        converter ?: synchronized(this) { converter ?: ZhConverter.open(map(context, "han/zh-convert.bin")).also { converter = it } }

    /** Drops the mapped lexicon after the pack was installed or deleted. */
    fun reload() {
        synchronized(this) { lexicon = null }
        _generation.value++
    }

    private fun load(context: Context): LexiconReader {
        if (HanPackManager.isInstalled(context)) {
            try {
                return LexiconReader.open(HanPackManager.packFile(context))
            } catch (e: Exception) {
                // A pack this engine can't read (an older format, say) is no worse than no pack.
                Log.w("Zizai", "modern pack unreadable, using the core lexicon", e)
            }
        }
        return LexiconReader(map(context, "han/han-core.bin"))
    }

    private fun map(context: Context, path: String): ByteBuffer {
        context.assets.openFd(path).use { fd ->
            // The mapping stays valid after the descriptor is closed.
            return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
        }
    }
}
