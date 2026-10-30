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
import io.zizai.ZizaiFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * The downloadable modern vocabulary pack, `han-ext-modern.bin` (docs/IMPLEMENTATION_PLAN.md P1-08).
 * It is a complete lexicon (the core data plus CC-CEDICT readings and OpenSubtitles counts, CC BY-SA 4.0),
 * so while it's installed it replaces the bundled `han-core.bin`, and deleting it falls back cleanly.
 * Follows upstream's `PinyinPackManager`: size and SHA-256 checks, then an atomic move into place.
 */
object HanPackManager {
    const val FILE_NAME = "han-ext-modern.bin"

    /** Bump the `-v1` when the pack is rebuilt: installed apps verify against the size and hash below. */
    private const val URL = "${ZizaiFlags.RELEASES_BASE}/han-packs-v1/$FILE_NAME"
    const val SIZE_BYTES = 6_851_468L
    private const val SHA256 = "04b0b7993d687c889815b89ad75875d167944667af458a665310284c8ff13b35"

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val _progress = MutableStateFlow<Int?>(null)

    /** Download progress in percent, or null when no download is running. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    fun packFile(context: Context): File = File(File(context.noBackupFilesDir, "han"), FILE_NAME)

    fun isInstalled(context: Context): Boolean = packFile(context).let { it.exists() && it.length() == SIZE_BYTES }

    /** Downloads and installs the pack, then switches the keyboard to it. Throws on failure. */
    suspend fun install(context: Context) {
        val appContext = context.applicationContext
        _progress.value = 0
        try {
            download(appContext) { done -> _progress.value = ((done * 100) / SIZE_BYTES).toInt().coerceIn(0, 100) }
        } finally {
            _progress.value = null
        }
        HanAssets.reload()
    }

    /** Removes the pack and switches back to the bundled core. Returns true if it is gone. */
    fun delete(context: Context): Boolean {
        val gone = packFile(context).let { !it.exists() || it.delete() }
        HanAssets.reload()
        return gone
    }

    private suspend fun download(context: Context, onProgress: (Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val dest = packFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.delete()
        try {
            client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code} downloading $URL" }
                val digest = MessageDigest.getInstance("SHA-256")
                response.body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(done)
                        }
                    }
                }
                check(tmp.length() == SIZE_BYTES) { "size mismatch for $FILE_NAME: ${tmp.length()}" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(SHA256, ignoreCase = true)) { "checksum mismatch for $FILE_NAME" }
            }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not move $FILE_NAME into place" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
