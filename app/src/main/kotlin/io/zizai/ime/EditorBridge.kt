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

    fun apply(edits: List<EditorEdit>) {
        if (edits.isEmpty()) return
        val ic = ims.currentInputConnection ?: return
        ic.beginBatchEdit()
        for (e in edits) {
            when (e) {
                is EditorEdit.SetPreedit -> ic.setComposingText(e.text, 1)
                is EditorEdit.Commit -> ic.commitText(e.text, 1)
                is EditorEdit.MarkComposing -> ic.setComposingRegion(e.start, e.end)
                EditorEdit.FinishComposing -> ic.finishComposingText()
            }
        }
        ic.endBatchEdit()
        pendingUpdates++
    }

    /** Called for every `onUpdateSelection`; true if it was the echo of our own edit. */
    fun consumeUpdate(): Boolean {
        if (pendingUpdates == 0) return false
        pendingUpdates--
        return true
    }

    fun resetUpdates() {
        pendingUpdates = 0
    }

    override fun textBeforeCursor(n: Int): CharSequence? = ims.currentInputConnection?.getTextBeforeCursor(n, 0)

    override fun textAfterCursor(n: Int): CharSequence? = ims.currentInputConnection?.getTextAfterCursor(n, 0)

    /** Whether the text just before the cursor now reads [expected] (spike S3's read-back check). */
    fun readsBack(expected: String): Boolean = textBeforeCursor(expected.length)?.toString() == expected

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
}
