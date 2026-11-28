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

/**
 * The rules behind two physical-keyboard shortcuts, apart from Android so they can be tested: a Shift
 * tap switching between two languages, and digits picking an English word from the floating panel.
 */
object HardwareShortcuts {
    /** A keyboard language as Shift sees it: its subtype id, and whether it is Chinese (or Cantonese). */
    data class Language(val id: Long, val chinese: Boolean)

    /**
     * The language a Shift tap switches to, or null when there is no other language (then Chinese
     * toggles 中/英 within itself). The pair is Chinese and not-Chinese where the list allows it: the
     * language Shift last left, if it is on the other side, else the first one on the other side. With
     * no other side (English and French, say) it goes back to the one it left, or the next one.
     */
    fun shiftTarget(active: Language, languages: List<Language>, lastLeft: Long?): Long? {
        val others = languages.filter { it.id != active.id }
        if (others.isEmpty()) return null
        val otherSide = others.filter { it.chinese != active.chinese }.ifEmpty { others }
        return (otherSide.firstOrNull { it.id == lastLeft } ?: otherSide.first()).id
    }

    /** Which word a digit picks: 1–9 the first nine, 0 the tenth; null for anything else. */
    fun digitIndex(codePoint: Int): Int? = when (codePoint) {
        in '1'.code..'9'.code -> codePoint - '1'.code
        '0'.code -> 9
        else -> null
    }

    /** Whether a language tag is Chinese or Cantonese, whatever the input method. */
    fun isChineseTag(tag: String): Boolean = tag.lowercase().let { it.startsWith("zh") || it.startsWith("yue") }
}
