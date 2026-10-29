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

import android.os.Trace
import android.util.Log
import dev.patrickgold.florisboard.BuildConfig

/**
 * Times the session's key handling (P1-20). Every key gets a `zizai.onKey` trace section for Perfetto;
 * builds other than release also log the 95th percentile of the last [WINDOW] keys every [WINDOW] keys, under the tag
 * `ZizaiLatency`, against the 2 ms budget (docs/ARCHITECTURE.md §6.10).
 */
object KeyLatency {
    private const val WINDOW = 50
    private val samples = LongArray(WINDOW)
    private var count = 0

    inline fun <T> measure(block: () -> T): T {
        Trace.beginSection("zizai.onKey")
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            record(System.nanoTime() - t0)
            Trace.endSection()
        }
    }

    fun record(nanos: Long) {
        if (BuildConfig.BUILD_TYPE == "release") return
        samples[count % WINDOW] = nanos
        count++
        if (count % WINDOW == 0) {
            val sorted = samples.sorted()
            val p95 = sorted[(WINDOW * 0.95).toInt()] / 1e6
            val p50 = sorted[WINDOW / 2] / 1e6
            Log.i("ZizaiLatency", "last $WINDOW keys: p50 %.2f ms, p95 %.2f ms (budget 2 ms)".format(p50, p95))
        }
    }
}
