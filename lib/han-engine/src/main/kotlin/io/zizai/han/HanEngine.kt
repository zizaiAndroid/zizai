/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han

/** Version constants shared by the engine and the dictionary builder (docs/ARCHITECTURE.md §6.8). */
object HanEngine {
    /**
     * Version of the `han-core.bin` format. Bump it whenever the layout changes or a syllable table
     * changes, because lexicon files store syllable ids.
     */
    const val LEXICON_FORMAT = 1
}
