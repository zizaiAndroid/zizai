/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai

import android.content.Context
import android.os.Process
import android.util.Log
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.jetpref.datastore.model.PreferenceModel
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.DataStore
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import dev.patrickgold.jetpref.datastore.runtime.jetprefDatastoreDir
import java.io.File

/**
 * Debug builds log every read and write of the settings file (tag `ZizaiPrefs`): process, thread, the
 * keyboard languages and one switch, so a write that puts back older settings shows who made it and
 * when. Settings were seen to roll back to a copy minutes old on a test phone; JetPref itself logs
 * nothing. Release builds load and save exactly as `initAndroid` does.
 */
object PrefsWriteLog {
    private const val TAG = "ZizaiPrefs"
    private val LOCALE = Regex("""primaryLocale\\":\\"([^\\"]*)""")

    /** `initAndroid`, with a logging reader and writer around the same file in debug builds. */
    suspend fun <T : PreferenceModel> init(store: DataStore<T>, context: Context, name: String): Result<Unit> {
        val file = File(context.jetprefDatastoreDir, "$name.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
        val storage = FileBasedStorage(file.absolutePath)
        if (!BuildConfig.DEBUG) {
            return store.init(LoadStrategy.UseReader(storage), PersistStrategy.UseWriter(storage))
        }
        val reader = DataStoreReader { storage.read().also { log("read", it) } }
        val writer = DataStoreWriter { content -> log("write", content); storage.write(content) }
        return store.init(LoadStrategy.UseReader(reader), PersistStrategy.UseWriter(writer))
    }

    private fun log(what: String, content: String) {
        val locales = LOCALE.findAll(content).map { it.groupValues[1] }.toList()
        val labels = content.lineSequence().firstOrNull { "display_keyboard_labels" in it }?.substringAfterLast(';')
        Log.i(
            TAG,
            "$what pid=${Process.myPid()} thread=${Thread.currentThread().name} bytes=${content.length} " +
                "languages=$locales labels=$labels",
        )
    }
}
