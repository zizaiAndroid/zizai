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

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import io.zizai.han.session.Key
import io.zizai.han.session.KeyStroke

/** What the router asks of the IME; [ZizaiIme] implements it. */
interface KeyRouterHost {
    val editorInfo: EditorInfo?
    val chineseSubtype: Boolean
    var chineseMode: Boolean
    val sessionActive: Boolean

    /** Sends a stroke to the session; returns whether the key is consumed. */
    fun dispatch(stroke: KeyStroke): Boolean
    fun commitRawComposition()
    fun enterHardwareMode()
    fun toggleDictation()
    fun cancelDictation(): Boolean
    fun pushToTalk(down: Boolean)
    fun toggleFullWidth()
}

/**
 * The hardware key router (docs/ARCHITECTURE.md §6.1, P1-12): normalizes key events into [KeyStroke]s
 * and routes them by the first matching rule. A key-up is consumed exactly when its key-down was, so the
 * app never sees half a key press.
 */
class KeyRouter(private val host: KeyRouterHost) {
    private val consumedDowns = HashSet<Int>()
    private var shiftDownAt = -1L
    private var otherKeySinceShift = false
    private var rightCtrlDownAt = -1L
    private var otherKeySinceRightCtrl = false
    private var pushToTalkActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val startPushToTalk = Runnable {
        if (rightCtrlDownAt >= 0 && !otherKeySinceRightCtrl) {
            pushToTalkActive = true
            host.enterHardwareMode()
            host.pushToTalk(down = true)
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return false
        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT) otherKeySinceShift = true
        if (keyCode != KeyEvent.KEYCODE_CTRL_RIGHT) otherKeySinceRightCtrl = true

        // Rule 1: no editor, or one that takes raw keys (terminals, games).
        val info = host.editorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) return false

        val consumed = route(keyCode, event, info)
        if (consumed) consumedDowns += keyCode
        return consumed
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                // Rule 7: a Shift tap toggles 中/英. Shift itself is never consumed, so the app's state stays right.
                val tap = shiftDownAt >= 0 && !otherKeySinceShift && SystemClock.uptimeMillis() - shiftDownAt < SHIFT_TAP_MS
                shiftDownAt = -1
                if (tap && host.chineseSubtype && host.editorInfo?.let(::isTextField) == true) {
                    if (host.sessionActive) host.commitRawComposition()
                    host.chineseMode = !host.chineseMode
                }
            }
            KeyEvent.KEYCODE_CTRL_RIGHT -> {
                handler.removeCallbacks(startPushToTalk)
                rightCtrlDownAt = -1
                if (pushToTalkActive) {
                    pushToTalkActive = false
                    host.pushToTalk(down = false)
                }
            }
        }
        return consumedDowns.remove(keyCode)
    }

    private fun route(keyCode: Int, event: KeyEvent, info: EditorInfo): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (event.repeatCount == 0) {
                    shiftDownAt = SystemClock.uptimeMillis()
                    otherKeySinceShift = false
                }
                return false
            }
            KeyEvent.KEYCODE_CTRL_RIGHT -> {
                // Rule 2: push-to-talk on a held Right Ctrl. Chords still work: the next key's metaState has Ctrl.
                if (event.repeatCount == 0) {
                    rightCtrlDownAt = SystemClock.uptimeMillis()
                    otherKeySinceRightCtrl = false
                    handler.postDelayed(startPushToTalk, PUSH_TO_TALK_MS)
                }
                return false
            }
        }

        // Rule 2: global hotkeys.
        if (event.isCtrlPressed && event.isShiftPressed && keyCode == KeyEvent.KEYCODE_SPACE) {
            host.enterHardwareMode()
            host.toggleDictation()
            return true
        }
        if (event.isCtrlPressed && !event.isShiftPressed && keyCode == KeyEvent.KEYCODE_PERIOD) {
            host.toggleFullWidth()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && !host.sessionActive && host.cancelDictation()) return true

        val textField = isTextField(info)
        val stroke = normalize(keyCode, event) ?: return false
        if (stroke.key == Key.CHAR && !stroke.hasCommandModifier) host.enterHardwareMode()

        // Rules 3–6: reconversion, composition, and idle Chinese input go through the session.
        val chinese = host.chineseSubtype && host.chineseMode && textField
        if (!host.sessionActive && !chinese) return false
        return host.dispatch(stroke)
    }

    private fun normalize(keyCode: Int, event: KeyEvent): KeyStroke? {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> if (event.isAltPressed) Key.FLIP else Key.SPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
            KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
            KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
            KeyEvent.KEYCODE_TAB -> Key.TAB
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_F2 -> Key.FLIP
            else -> null
        }
        val shift = event.isShiftPressed
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed && key != Key.FLIP
        val meta = event.isMetaPressed
        if (key != null) return KeyStroke(key, 0, shift, ctrl, alt, meta, event.repeatCount)
        // getUnicodeChar honours the Alt and Sym layers of compact keyboards.
        val cp = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv())
        if (cp <= 0 || (cp and KeyCharacterMapCombining) != 0) return null
        return KeyStroke(Key.CHAR, cp, shift, ctrl, alt, meta, event.repeatCount)
    }

    /** Fields that start in English (docs/ARCHITECTURE.md §6.1): passwords, email, URLs, numbers. */
    private fun isTextField(info: EditorInfo): Boolean {
        if (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0) return false
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI -> false
            else -> true
        }
    }

    fun reset() {
        consumedDowns.clear()
        handler.removeCallbacks(startPushToTalk)
        rightCtrlDownAt = -1
        shiftDownAt = -1
    }

    private companion object {
        const val SHIFT_TAP_MS = 300L
        const val PUSH_TO_TALK_MS = 250L
        const val KeyCharacterMapCombining = 0x80000000.toInt()
    }
}
