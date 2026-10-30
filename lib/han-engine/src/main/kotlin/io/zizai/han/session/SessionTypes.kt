/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.han.session

import io.zizai.han.candidate.Candidate
import io.zizai.han.candidate.HomophoneFilter

/** Keys the session cares about; the hardware router (P1-12) maps Android key codes onto these. */
enum class Key {
    /** A printable character, in [KeyStroke.codePoint]. */
    CHAR,
    SPACE, ENTER, ESCAPE, BACKSPACE, DELETE, TAB,
    LEFT, RIGHT, UP, DOWN, PAGE_UP, PAGE_DOWN,

    /** The flip key: F2 or Alt+Space by default. */
    FLIP,

    /** Anything else. */
    OTHER,
}

/** One normalized key press (docs/ARCHITECTURE.md §6.1). */
data class KeyStroke(
    val key: Key,
    val codePoint: Int = 0,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val repeatCount: Int = 0,
) {
    val hasCommandModifier: Boolean get() = ctrl || alt || meta

    companion object {
        fun char(c: Char, shift: Boolean = false) = KeyStroke(Key.CHAR, c.code, shift = shift)
        fun of(key: Key, shift: Boolean = false, ctrl: Boolean = false) = KeyStroke(key, shift = shift, ctrl = ctrl)
    }
}

sealed interface KeyResult {
    /** The session didn't want the key; the app gets it unchanged. */
    data object NotHandled : KeyResult

    /**
     * The session took the key and wants [edits] applied in order, in one batch edit. With [passThrough],
     * the app still gets the key afterwards (a printable key that confirms a flip, then types).
     */
    data class Handled(val edits: List<EditorEdit>, val passThrough: Boolean = false) : KeyResult
}

/** Changes to the editor, applied by the IME through `InputConnection`. */
sealed interface EditorEdit {
    /** Replace the composing text (or the marked region) with [text], cursor at [cursor] within it. */
    data class SetPreedit(val text: String, val cursor: Int = text.length) : EditorEdit

    /** Commit [text] in place of the composing text. */
    data class Commit(val text: String) : EditorEdit

    /** Make the existing text [start]..[end] (absolute, UTF-16) the composing region, for reconversion. */
    data class MarkComposing(val start: Int, val end: Int) : EditorEdit

    /** Keep whatever is composing as committed text. */
    data object FinishComposing : EditorEdit
}

enum class SessionMode { IDLE, COMPOSING, EXPANDED, RECONVERTING }

enum class ResetReason { FIELD_CHANGED, COMPOSITION_LOST, MODE_SWITCH, ERROR }

/** What the session needs to know about the editor; the IME implements it over `InputConnection`. */
interface EditorContext {
    /** Up to [n] UTF-16 units before the cursor, or null if the editor won't say. */
    fun textBeforeCursor(n: Int): CharSequence?

    /** Up to [n] UTF-16 units after the cursor, or null if the editor won't say. */
    fun textAfterCursor(n: Int): CharSequence? = null

    /** Absolute UTF-16 offset of the cursor, or -1 if unknown. */
    fun cursorPosition(): Int

    object None : EditorContext {
        override fun textBeforeCursor(n: Int): CharSequence? = null
        override fun cursorPosition(): Int = -1
    }
}

enum class PunctuationStyle {
    /** Mainland: “ ” and ‘ ’. */
    MAINLAND,

    /** Taiwan and Hong Kong: 「 」 and 『 』. */
    TRADITIONAL,
}

data class HanSettings(
    val pageSize: Int = 9,
    val fullWidthPunctuation: Boolean = true,
    val punctuationStyle: PunctuationStyle = PunctuationStyle.MAINLAND,
    val filter: HomophoneFilter = HomophoneFilter(),
    /** False in incognito mode, password fields and fields that ask for no personalized learning. */
    val learning: Boolean = true,
    val scheme: io.zizai.han.parse.PinyinScheme = io.zizai.han.parse.PinyinScheme.QUANPIN,
    val fuzzy: Set<io.zizai.han.parse.Fuzzy> = emptySet(),
    /** Flip by Cantonese readings: dictation is in Cantonese (P2-10). */
    val flipCantonese: Boolean = false,
)

/**
 * The text to reconvert: the character at [start]..[end] in the editor, with up to a few characters of
 * context on each side, which pick its reading (银行 → háng) and rank the options (在见 → 再见).
 */
data class ReconversionTarget(
    val text: String,
    val start: Int,
    val end: Int,
    val before: String = "",
    val after: String = "",
    /** Options to offer right after the original, such as a proofreading model's suggestions (P2-08). */
    val preferred: List<String> = emptyList(),
)

/** One row of the expanded grid: a tone-marked syllable and its characters. */
data class GridRow(val label: String, val tone: Int, val characters: List<String>)

/** An immutable snapshot for the candidate panel and the Smartbar strip. */
data class HanUiState(
    val mode: SessionMode = SessionMode.IDLE,
    /** What the panel shows as the composition: selected text, then the spelled Pinyin still to convert. */
    val preedit: String = "",
    val candidates: List<Candidate> = emptyList(),
    val highlighted: Int = 0,
    val page: Int = 0,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
    /** 0 for all tones, else 1–5. */
    val toneFilter: Int = 0,
    val grid: List<GridRow> = emptyList(),
    val gridRow: Int = 0,
    val gridColumn: Int = 0,
) {
    companion object {
        val Idle = HanUiState()
    }
}
