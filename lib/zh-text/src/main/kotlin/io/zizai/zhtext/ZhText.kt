/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.zhtext

/** Chinese text utilities; filled in by P1-09 and P1-10 (docs/IMPLEMENTATION_PLAN.md §3.2). */
object ZhText {
    /** True for a code point in the CJK Unified Ideographs blocks (base and extensions A–I). */
    fun isHan(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}
