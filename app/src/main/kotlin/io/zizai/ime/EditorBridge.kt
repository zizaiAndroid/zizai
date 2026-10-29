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

    override fun cursorPosition(): Int {
        val et = ims.currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        return et.startOffset + et.selectionStart
    }
}
