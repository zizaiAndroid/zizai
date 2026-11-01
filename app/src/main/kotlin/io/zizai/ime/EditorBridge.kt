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

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import io.zizai.han.session.EditorContext
import io.zizai.han.session.EditorEdit
import io.zizai.han.session.EditorSelection

/**
 * Applies the session's [EditorEdit]s through the current `InputConnection`, in one batch edit, and
 * answers the session's questions about the editor (docs/ARCHITECTURE.md §6.4).
 */
class EditorBridge(private val ims: InputMethodService) : EditorContext {
    /** Batches sent whose `onUpdateSelection` hasn't arrived yet; see [ZizaiIme.onUpdateSelection]. */
    var pendingUpdates = 0
        private set

    /** Where the flip's text starts, and how long it is now; null outside a flip. */
    private var regionStart = -1
    private var regionLength = 0

    /** Up to [ANCHOR] characters just before the flip's text, so the read-back sees a misplaced change. */
    private var anchor: String? = null

    /**
     * This editor ignored a composing region, so flips go through `replaceText` (API 34+) until the next
     * field. Nothing is underlined then, but the candidates still show what is being flipped.
     */
    private var replacing = false

    fun apply(edits: List<EditorEdit>) {
        if (edits.isEmpty()) return
        val ic = ims.currentInputConnection ?: return
        edits.firstOrNull { it is EditorEdit.MarkComposing }?.let { recordAnchor(it as EditorEdit.MarkComposing) }
        ic.beginBatchEdit()
        for (e in edits) {
            when (e) {
                is EditorEdit.SetPreedit -> if (replacing && regionStart >= 0 && Build.VERSION.SDK_INT >= 34) {
                    ic.replaceText(regionStart, regionStart + regionLength, e.text, 1, null)
                    regionLength = e.text.length
                } else {
                    ic.setComposingText(e.text, 1)
                    if (regionStart >= 0) regionLength = e.text.length
                }
                is EditorEdit.Commit -> ic.commitText(e.text, 1)
                is EditorEdit.MarkComposing -> {
                    regionStart = e.start
                    regionLength = e.end - e.start
                    if (!replacing) ic.setComposingRegion(e.start, e.end)
                }
                EditorEdit.FinishComposing -> {
                    regionStart = -1
                    anchor = null
                    ic.finishComposingText()
                }
            }
        }
        ic.endBatchEdit()
        pendingUpdates++
    }

    private fun recordAnchor(mark: EditorEdit.MarkComposing) {
        anchor = null
        val length = mark.end - mark.start
        anchor = when (cursorPosition()) {
            // The flip of the character before the cursor.
            mark.end -> textBeforeCursor(length + ANCHOR)?.toString()?.takeIf { it.length >= length }?.dropLast(length)
            // A selection: text before the cursor stops at its start.
            mark.start -> textBeforeCursor(ANCHOR)?.toString()
            else -> null
        }
    }

    /**
     * After a read-back mismatch: on API 34+, puts [expected] where the flip's text was with `replaceText`,
     * and keeps using it for this field if that reads back. False when that isn't possible or didn't work.
     */
    fun replaceInstead(expected: String): Boolean {
        if (Build.VERSION.SDK_INT < 34 || replacing || regionStart < 0 || anchor == null) return false
        val ic = ims.currentInputConnection ?: return false
        // Whatever landed between the flip's start and the cursor (the old text, a misplaced preview) goes.
        val cursor = cursorPosition()
        if (cursor < regionStart) return false
        ic.beginBatchEdit()
        ic.setComposingText("", 1)
        ic.finishComposingText()
        ic.endBatchEdit()
        val end = maxOf(cursorPosition(), regionStart)
        if (!ic.replaceText(regionStart, end, expected, 1, null)) return false
        pendingUpdates += 2
        regionLength = expected.length
        replacing = true
        return readsBack(expected)
    }

    /** Called for every `onUpdateSelection`; true if it was the echo of our own edit. */
    fun consumeUpdate(): Boolean {
        if (pendingUpdates == 0) return false
        pendingUpdates--
        return true
    }

    fun resetUpdates() {
        pendingUpdates = 0
        regionStart = -1
        anchor = null
    }

    /** A new field: try composing regions again. */
    fun onNewField() {
        resetUpdates()
        replacing = false
    }

    override fun textBeforeCursor(n: Int): CharSequence? = ims.currentInputConnection?.getTextBeforeCursor(n, 0)

    override fun textAfterCursor(n: Int): CharSequence? = ims.currentInputConnection?.getTextAfterCursor(n, 0)

    /**
     * Whether the text just before the cursor now reads [expected] (spike S3's read-back check), right
     * after the text that was before the flip: a preview typed at the cursor instead of over the old
     * character also ends in [expected], but not after the right text.
     */
    fun readsBack(expected: String): Boolean {
        val wanted = anchor.orEmpty() + expected
        return textBeforeCursor(wanted.length)?.toString() == wanted
    }

    override fun cursorPosition(): Int {
        val et = ims.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        return et.startOffset + et.selectionStart
    }

    override fun selection(): EditorSelection? {
        val ic = ims.currentInputConnection ?: return null
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val start = minOf(et.selectionStart, et.selectionEnd)
        val end = maxOf(et.selectionStart, et.selectionEnd)
        if (start < 0 || start == end) return null
        val text = ic.getSelectedText(0)?.toString() ?: return null
        return EditorSelection(et.startOffset + start, et.startOffset + end, text)
    }

    private companion object {
        const val ANCHOR = 8
    }
}
