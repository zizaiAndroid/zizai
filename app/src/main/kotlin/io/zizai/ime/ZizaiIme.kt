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
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.subtypeManager
import io.zizai.han.session.DefaultHanSession
import io.zizai.han.session.HanSession
import io.zizai.han.session.HanSettings
import io.zizai.han.session.KeyResult
import io.zizai.han.session.KeyStroke
import io.zizai.han.session.ResetReason
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The single entry point for Zizai's Chinese input in the IME service (docs/IMPLEMENTATION_PLAN.md P1-11).
 * Every upstream hook in `FlorisImeService` is one `// ZIZAI-HOOK:` line calling into this class; with no
 * Chinese Pinyin subtype active and no hardware keyboard in use, it changes nothing.
 */
class ZizaiIme(private val ims: InputMethodService) : KeyRouterHost {
    private val subtypeManager by ims.subtypeManager()
    private val editor = EditorBridge(ims)
    private val hardware = HardwareMode(ims)
    private val router = KeyRouter(this)
    private var settings = HanSettings()

    private val session: HanSession by lazy {
        DefaultHanSession(HanAssets.lexicon(ims), settings = { settings }, editor = editor)
    }

    override var chineseMode: Boolean = true
        set(value) {
            field = value
            renderPill()
        }

    // ---- Hooks ----

    /** After the upstream root view is added to the window's content. */
    fun attach(content: ViewGroup) {
        hardware.attach(content)
        hardware.pill.setOnClickListener { if (sessionActive) commitRawComposition(); chineseMode = !chineseMode }
        val owner = ims as LifecycleOwner
        owner.lifecycleScope.launch {
            combine(session.ui, DictateController.state) { ui, _ -> ui }.collect { ui ->
                hardware.panel.render(ui)
                renderPill()
                if (hardware.active) hardware.position()
            }
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = guard(false) { router.onKeyDown(keyCode, event) }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = guard(false) { router.onKeyUp(keyCode, event) }

    /** The window stays shown in hardware mode (spike S2: the microphone needs it). */
    val keepsWindowShown: Boolean get() = hardware.active

    /** Returns true when hardware mode supplied the insets. */
    fun computeInsets(outInsets: InputMethodService.Insets): Boolean {
        if (!hardware.active) return false
        hardware.computeInsets(outInsets)
        return true
    }

    fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (hardware.active) hardware.onUpdateCursorAnchorInfo(info)
    }

    /**
     * Returns true when upstream must not reconcile the selection: in hardware mode the session owns the
     * composing text (spike S1 showed upstream's reconciliation duplicating it). If the app dropped our
     * composition (it cleared the field, say), the session resets instead of fighting it.
     */
    fun onUpdateSelection(candidatesStart: Int): Boolean {
        val echo = editor.consumeUpdate()
        if (!echo && session.isActive && candidatesStart < 0) {
            session.reset(ResetReason.COMPOSITION_LOST)
        }
        return hardware.active || session.isActive
    }

    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        if (!restarting) resetAll(ResetReason.FIELD_CHANGED)
    }

    /** A tap in the editor: back to the touch keyboard. */
    fun onViewClicked() {
        if (session.isActive) commitRawComposition()
        hardware.exit()
    }

    /** API 34+: a finger or stylus in the editor means touch mode. */
    fun onUpdateEditorToolType(toolType: Int) {
        if (toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS) onViewClicked()
    }

    fun onFinishInputView() {
        resetAll(ResetReason.FIELD_CHANGED)
        hardware.exit()
    }

    // ---- KeyRouterHost ----

    override val editorInfo: EditorInfo? get() = ims.currentInputEditorInfo

    override val chineseSubtype: Boolean
        get() = subtypeManager.activeSubtype.primaryLocale.languageTag().lowercase().let {
            it.startsWith("zh") && "pinyin" in it
        }

    override val sessionActive: Boolean get() = session.isActive

    override fun dispatch(stroke: KeyStroke): Boolean = when (val r = session.onKey(stroke)) {
        KeyResult.NotHandled -> false
        is KeyResult.Handled -> {
            if (session.isActive) hardware.enter()
            editor.apply(r.edits)
            !r.passThrough
        }
    }

    override fun commitRawComposition() {
        (session.commitRaw() as? KeyResult.Handled)?.let { editor.apply(it.edits) }
    }

    override fun enterHardwareMode() = hardware.enter()

    override fun toggleDictation() = DictateController.onMicClick(ims)

    override fun cancelDictation(): Boolean {
        if (DictateController.state.value !is DictateController.UiState.Recording) return false
        DictateController.cancelRecording()
        return true
    }

    override fun pushToTalk(down: Boolean) {
        if (down) DictateController.onPushToTalkDown(ims) else DictateController.onPushToTalkUp(ims, send = true)
    }

    override fun toggleFullWidth() {
        settings = settings.copy(fullWidthPunctuation = !settings.fullWidthPunctuation)
    }

    // ---- Internals ----

    private fun resetAll(reason: ResetReason) {
        if (session.isActive) session.reset(reason)
        editor.resetUpdates()
        router.reset()
    }

    private fun renderPill() {
        hardware.pill.render(
            chinese = chineseSubtype && chineseMode,
            recording = DictateController.state.value is DictateController.UiState.Recording,
        )
    }

    /** Any exception inside our code resets the session and lets the key through (P1-20). */
    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.e("Zizai", "key handling failed; resetting", e)
        runCatching { session.reset(ResetReason.ERROR) }
        router.reset()
        fallback
    }
}
