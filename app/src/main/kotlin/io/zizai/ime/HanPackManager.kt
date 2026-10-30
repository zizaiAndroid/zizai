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

    /**
     * Bump this when the pack is rebuilt, with a new release tag and the size and hash below: installed
     * apps verify against them. The local file carries the version, so an older pack is never mistaken
     * for the current one.
     */
    private const val VERSION = 2
    private const val URL = "${ZizaiFlags.RELEASES_BASE}/han-packs-v$VERSION/$FILE_NAME"
    const val SIZE_BYTES = 6_851_468L
    private const val SHA256 = "f9514d9de1a238ce0c4c12e3aebe75a2589c4937e1205e953576f7c2c38ea4a1"

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val _progress = MutableStateFlow<Int?>(null)

    /** Download progress in percent, or null when no download is running. */
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    private fun packDir(context: Context) = File(context.noBackupFilesDir, "han")

    fun packFile(context: Context): File = File(packDir(context), "han-ext-modern-v$VERSION.bin")

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
        deleteOlderPacks(appContext)
        HanAssets.reload()
    }

    /** Removes the pack and switches back to the bundled core. Returns true if it is gone. */
    fun delete(context: Context): Boolean {
        deleteOlderPacks(context)
        val gone = packFile(context).let { !it.exists() || it.delete() }
        HanAssets.reload()
        return gone
    }

    /** Packs from earlier versions, including v1's unversioned file name. */
    private fun deleteOlderPacks(context: Context) {
        val current = packFile(context).name
        packDir(context).listFiles()?.filter { it.name.startsWith("han-ext-modern") && it.name != current }?.forEach { it.delete() }
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
