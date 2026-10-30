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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import io.zizai.han.candidate.CandidateEngine
import dev.patrickgold.florisboard.subtypeManager
import io.zizai.ZizaiPrefs
import io.zizai.han.session.DefaultHanSession
import io.zizai.han.session.HanSession
import io.zizai.han.session.HanSettings
import io.zizai.han.session.Key
import io.zizai.han.session.KeyResult
import io.zizai.han.session.KeyStroke
import io.zizai.han.session.ResetReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * The single entry point for Zizai's Chinese input in the IME service (docs/IMPLEMENTATION_PLAN.md P1-11).
 * Every upstream hook is one `// ZIZAI-HOOK:` line calling into this class; with no Chinese Pinyin subtype
 * active and no hardware keyboard in use, it changes nothing.
 *
 * The hardware keyboard (through [KeyRouter]) and the touch keyboard (through `KeyboardManager` hooks)
 * drive the same [HanSession], so both get the same candidates, segmentation and paging (P1-16).
 */
class ZizaiIme(private val ims: InputMethodService) : KeyRouterHost {
    init {
        current = this
    }

    private val subtypeManager by ims.subtypeManager()
    private val nlpManager by ims.nlpManager()
    private val keyboardManager by ims.keyboardManager()
    private val appPrefs by FlorisPreferenceStore
    private val prefs by lazy { ZizaiPrefs.get(ims) } // lazy: the service has no context while constructing
    private val editor = EditorBridge(ims)
    private val hardware = HardwareMode(ims)
    private val router = KeyRouter(this)

    /** The session, rebuilt when the lexicon changes (the modern pack was installed or deleted, P1-08). */
    private val sessions = MutableStateFlow<HanSession?>(null)
    private var sessionGeneration = -1

    private val session: HanSession
        get() {
            val generation = HanAssets.generation.value
            val existing = sessions.value
            if (existing != null && generation == sessionGeneration) return existing
            existing?.reset(ResetReason.MODE_SWITCH)
            val lexicon = HanAssets.lexicon(ims)
            val engine = CandidateEngine(lexicon, HanUserDictionary.get(ims))
            val cantonese = runCatching { HanAssets.cantonese(ims) }.getOrNull()
            return DefaultHanSession(lexicon, engine, settings = ::settings, editor = editor, cantonese = cantonese).also {
                sessionGeneration = generation
                sessions.value = it
            }
        }

    private fun settings() = HanSettings(
        pageSize = prefs.pageSize,
        fullWidthPunctuation = prefs.fullWidthPunctuation,
        learning = learningAllowed(),
        scheme = prefs.scheme,
        fuzzy = prefs.fuzzy,
        flipCantonese = appPrefs.dictate.activeInputLanguage.get().startsWith("yue"),
    )

    /** Never learn in incognito mode, password fields, or fields that ask for no personalized learning. */
    private fun learningAllowed(): Boolean {
        if (keyboardManager.activeState.isIncognitoMode) return false
        val info = ims.currentInputEditorInfo ?: return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        return KeyRouter.isTextField(info)
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
        hardware.pill.setOnClickListener {
            if (sessionActive) commitRawComposition()
            chineseMode = !chineseMode
        }
        (ims as LifecycleOwner).lifecycleScope.launch {
            session // create it, so the flow below has one to follow
            @OptIn(ExperimentalCoroutinesApi::class)
            val ui = sessions.filterNotNull().flatMapLatest { it.ui }
            combine(ui, DictateController.state) { ui, _ -> ui }.collect { ui ->
                hardware.panel.render(if (hardware.active) ui else io.zizai.han.session.HanUiState.Idle)
                renderPill()
                if (hardware.active) hardware.position()
                nlpManager.refreshCandidates()
            }
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.hardwareMode == ZizaiPrefs.HardwareModeSetting.TOUCH && !session.isActive) return false
        return guard(false) { router.onKeyDown(keyCode, event) }
    }

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
     * Returns true when upstream must not reconcile the selection: while the session owns the composing
     * text (spike S1 showed upstream's reconciliation duplicating it). Otherwise upstream keeps tracking, or
     * its idea of the selection goes stale (it showed the prompt strip for a selection that wasn't there). If the app dropped
     * our composition (it cleared the field, say), the session resets instead of fighting it.
     */
    fun onUpdateSelection(candidatesStart: Int): Boolean {
        val echo = editor.consumeUpdate()
        if (!echo && session.isActive && candidatesStart < 0) {
            session.reset(ResetReason.COMPOSITION_LOST)
        }
        return session.isActive
    }

    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        if (!restarting) resetAll(ResetReason.FIELD_CHANGED)
    }

    /** A tap in the editor: back to the touch keyboard, unless the user always wants hardware mode. */
    fun onViewClicked() {
        if (session.isActive) commitRawComposition()
        if (prefs.hardwareMode != ZizaiPrefs.HardwareModeSetting.HARDWARE) hardware.exit()
    }

    /** API 34+: a finger or stylus in the editor means touch mode. */
    fun onUpdateEditorToolType(toolType: Int) {
        if (toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS) onViewClicked()
    }

    fun onFinishInputView() {
        resetAll(ResetReason.FIELD_CHANGED)
        hardware.exit()
    }

    // ---- Touch path (P1-16) ----

    /** From `KeyboardManager.onInputKeyUp`; true when the session took the key. */
    fun onTouchKey(data: KeyData): Boolean = guard(false) {
        val info = editorInfo ?: return@guard false
        if (!session.isActive && !(chineseSubtype && chineseMode && KeyRouter.isTextField(info))) return@guard false
        val stroke = when {
            data.code == KeyCode.DELETE -> KeyStroke(Key.BACKSPACE)
            data.code == KeyCode.SPACE -> KeyStroke(Key.SPACE)
            data.code == KeyCode.ENTER -> KeyStroke(Key.ENTER)
            data.type == KeyType.CHARACTER -> {
                val text = data.asString(isForDisplay = false)
                if (text.isEmpty()) return@guard false
                val cp = text.codePointAt(0)
                KeyStroke(Key.CHAR, cp, shift = Character.isUpperCase(cp))
            }
            else -> return@guard false
        }
        dispatch(stroke, fromHardware = false)
    }

    /**
     * The Smartbar's candidates while the session composes or flips; when idle after a Chinese character
     * in Chinese mode, the 改 chip that flips it (P1-18). Null lets upstream assemble its own.
     */
    fun touchCandidates(): List<SuggestionCandidate>? {
        if (!session.isActive) return flipChip()?.let(::listOf)
        return session.peekCandidates(TOUCH_CANDIDATES).mapIndexed { i, c -> HanSuggestionCandidate(c.text, i) }
    }

    private fun flipChip(): SuggestionCandidate? {
        if (hardware.active || !chineseSubtype || !chineseMode) return null
        val before = editor.textBeforeCursor(2)?.toString() ?: return null
        if (before.isEmpty()) return null
        val cp = before.codePointBefore(before.length)
        if (!io.zizai.zhtext.ZhText.isHan(cp)) return null
        return HanSuggestionCandidate("改 " + String(Character.toChars(cp)), HanSuggestionCandidate.FLIP_CHIP)
    }

    /** From `KeyboardManager.commitCandidate`; true when it was one of ours. */
    fun onTouchCandidate(candidate: SuggestionCandidate): Boolean = guard(false) {
        val han = candidate as? HanSuggestionCandidate ?: return@guard false
        if (han.index == HanSuggestionCandidate.FLIP_CHIP) {
            dispatch(KeyStroke(Key.FLIP), fromHardware = false)
            nlpManager.refreshCandidates()
            return@guard true
        }
        val size = prefs.pageSize
        (session.onCandidateTapped(han.index / size, han.index % size) as? KeyResult.Handled)?.let { editor.apply(it.edits) }
        verifyReconversion()
        true
    }

    // ---- KeyRouterHost ----

    override val editorInfo: EditorInfo? get() = ims.currentInputEditorInfo

    override val chineseSubtype: Boolean
        get() = subtypeManager.activeSubtype.primaryLocale.languageTag().lowercase().let {
            it.startsWith("zh") && "pinyin" in it
        }

    override val sessionActive: Boolean get() = session.isActive

    override fun dispatch(stroke: KeyStroke): Boolean = dispatch(stroke, fromHardware = true)

    private fun dispatch(stroke: KeyStroke, fromHardware: Boolean): Boolean = when (val r = KeyLatency.measure { session.onKey(stroke) }) {
        KeyResult.NotHandled -> false
        is KeyResult.Handled -> {
            if (fromHardware && session.isActive) enterHardwareMode()
            editor.apply(r.edits)
            verifyReconversion()
            !r.passThrough
        }
    }

    /**
     * After each flip step, read the text back (spike S3). An editor that didn't take the change gets the
     * original back rather than a mangled field (P1-18).
     */
    private fun verifyReconversion() {
        val ui = session.ui.value
        if (ui.mode != io.zizai.han.session.SessionMode.RECONVERTING) return
        if (editor.readsBack(ui.preedit)) return
        Log.w("Zizai", "flip read-back mismatch; restoring the original")
        (session.onKey(KeyStroke(Key.ESCAPE)) as? KeyResult.Handled)?.let { editor.apply(it.edits) }
    }

    override fun commitRawComposition() {
        (session.commitRaw() as? KeyResult.Handled)?.let { editor.apply(it.edits) }
    }

    override fun enterHardwareMode() {
        if (prefs.hardwareMode == ZizaiPrefs.HardwareModeSetting.TOUCH || hardware.active) return
        hardware.enter()
        // The first time: show the shortcuts once (P1-17 onboarding).
        if (!prefs.hardwareOnboardingShown) {
            prefs.hardwareOnboardingShown = true
            showCheatSheet()
        }
    }

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
        prefs.fullWidthPunctuation = !prefs.fullWidthPunctuation
    }

    private var cheatSheetShown = false

    override fun toggleCheatSheet() {
        if (cheatSheetShown) return hideCheatSheet()
        prefs.hardwareOnboardingShown = true // asked for it, so no need to show it unasked
        if (prefs.hardwareMode != ZizaiPrefs.HardwareModeSetting.TOUCH) hardware.enter()
        showCheatSheet()
    }

    override fun hideCheatSheet() {
        if (!cheatSheetShown) return
        cheatSheetShown = false
        hardware.panel.render(if (hardware.active) session.ui.value else io.zizai.han.session.HanUiState.Idle)
    }

    private fun showCheatSheet() {
        if (!hardware.active) return
        cheatSheetShown = true
        hardware.panel.showText(ims.getString(dev.patrickgold.florisboard.R.string.zizai__cheat_sheet))
        hardware.position()
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

    companion object {
        private const val TOUCH_CANDIDATES = 40

        /** The live instance, for hooks in upstream classes that can't reach the service's field. */
        @Volatile var current: ZizaiIme? = null
            private set
    }
}
