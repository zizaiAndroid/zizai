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
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.zizai.han.candidate.LearnedDictionary
import java.io.File
import java.util.concurrent.Executors

/**
 * The user's learned Chinese words and blocked candidates (docs/IMPLEMENTATION_PLAN.md P2-02), kept in
 * `files/zizai/han_user.tsv`. That folder is in the backup rules, so a restore brings the dictionary back.
 * Writes happen off the main thread, a moment after the last change, so typing never waits on disk.
 */
object HanUserDictionary {
    private const val SAVE_DELAY_MS = 2_000L

    @Volatile private var dict: LearnedDictionary? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var file: File? = null

    private val save = Runnable {
        val d = dict ?: return@Runnable
        val f = file ?: return@Runnable
        val text = d.serialize()
        io.execute {
            try {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                Log.w("Zizai", "could not save the user dictionary", e)
            }
        }
    }

    /** The dictionary if the keyboard has loaded it already, else null (it doesn't touch the disk). */
    fun loaded(): LearnedDictionary? = dict

    fun get(context: Context): LearnedDictionary = dict ?: synchronized(this) {
        dict ?: LearnedDictionary().also { d ->
            val f = File(File(context.applicationContext.filesDir, "zizai"), "han_user.tsv")
            file = f
            runCatching { if (f.exists()) d.load(f.readText()) }
                .onFailure { Log.w("Zizai", "could not read the user dictionary", it) }
            d.onChanged = {
                handler.removeCallbacks(save)
                handler.postDelayed(save, SAVE_DELAY_MS)
            }
            dict = d
        }
    }
}
