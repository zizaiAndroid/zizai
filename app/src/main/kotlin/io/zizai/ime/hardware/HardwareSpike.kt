/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ime.hardware

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.TextView

/**
 * SPIKE S1 (docs/IMPLEMENTATION_PLAN.md §3.1), throwaway code on branch `spike/s1-hardware-panel`.
 *
 * Answers one question: can the IME take letters from a hardware keyboard, render them as composing text,
 * and show a small panel at the caret (positioned from [CursorAnchorInfo]) while the app keeps its full
 * height? The "candidates" are dummies; nothing here is the real engine.
 */
class HardwareSpike(private val ims: InputMethodService) {
    var active = false
        private set

    private val buffer = StringBuilder()
    private val consumedDowns = mutableSetOf<Int>()
    private var content: ViewGroup? = null
    private var panel: TextView? = null
    private var caretScreen: FloatArray? = null
    private var anchorSource = "none"

    fun attach(content: ViewGroup) {
        this.content = content
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ims.resources.displayMetrics) }
        panel = TextView(ims).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60E7C66"))
                cornerRadius = dp(10f)
            }
            elevation = dp(6f)
            visibility = View.GONE
        }
        content.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ))
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        event ?: return false
        val info = ims.currentInputEditorInfo ?: return false
        val ic = ims.currentInputConnection ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) return false
        // SPIKE S2: Ctrl+Shift+Space toggles dictation, to test the microphone with the window shown or hidden.
        if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && event.isShiftPressed) {
            log("S2 toggle dictation: active=$active inputViewShown=${ims.isInputViewShown}")
            dev.patrickgold.florisboard.dictate.DictateController.onMicClick(ims)
            consumedDowns += keyCode
            return true
        }
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val ch = event.getUnicodeChar(event.metaState).toChar()
        val handled = when {
            ch in 'a'..'z' -> { enter(ic); buffer.append(ch); true }
            buffer.isEmpty() -> false
            keyCode == KeyEvent.KEYCODE_DEL -> { buffer.deleteCharAt(buffer.lastIndex); true }
            keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_1 -> { commit(ic, "是"); true }
            keyCode == KeyEvent.KEYCODE_2 -> { commit(ic, "事"); true }
            keyCode == KeyEvent.KEYCODE_ENTER -> { commit(ic, buffer.toString()); true }
            keyCode == KeyEvent.KEYCODE_ESCAPE -> { buffer.clear(); true }
            else -> false
        }
        if (handled) {
            consumedDowns += keyCode
            render(ic)
        }
        return handled
    }

    /** Key-ups are consumed exactly when their key-downs were, so the app never sees half a key press. */
    fun onKeyUp(keyCode: Int): Boolean = consumedDowns.remove(keyCode)

    fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val pts = when {
            !info.insertionMarkerHorizontal.isNaN() -> {
                anchorSource = "insertionMarker"
                floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerBottom)
            }
            info.composingTextStart >= 0 && buffer.isNotEmpty() -> {
                val last = info.getCharacterBounds(info.composingTextStart + buffer.length - 1)
                if (last == null) null else {
                    anchorSource = "composingBounds"
                    floatArrayOf(last.right, last.bottom)
                }
            }
            else -> null
        } ?: run { anchorSource = "none"; caretScreen = null; position(); return }
        info.matrix.mapPoints(pts)
        caretScreen = pts
        position()
    }

    fun computeInsets(outInsets: InputMethodService.Insets) {
        val root = content ?: return
        outInsets.contentTopInsets = root.height
        outInsets.visibleTopInsets = root.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        val p = panel
        if (p != null && p.visibility == View.VISIBLE) {
            outInsets.touchableRegion.set(p.x.toInt(), p.y.toInt(), (p.x + p.width).toInt(), (p.y + p.height).toInt())
        } else {
            outInsets.touchableRegion.setEmpty()
        }
    }

    fun exit() {
        if (!active) return
        active = false
        buffer.clear()
        consumedDowns.clear()
        panel?.visibility = View.GONE
        setOtherChildrenVisible(true)
        log("exit")
    }

    private fun enter(ic: InputConnection) {
        if (active) return
        active = true
        setOtherChildrenVisible(false)
        ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
        if (!ims.isInputViewShown) ims.requestShowSelf(0)
        log("enter")
    }

    private fun commit(ic: InputConnection, text: String) {
        ic.commitText(text, 1)
        buffer.clear()
    }

    private fun render(ic: InputConnection) {
        setOtherChildrenVisible(false)
        if (buffer.isEmpty()) ic.finishComposingText() else ic.setComposingText(buffer, 1)
        val p = panel ?: return
        if (buffer.isEmpty()) {
            p.visibility = View.GONE
        } else {
            p.text = "$buffer   1是  2事  3时  4市   [S1]"
            p.visibility = View.VISIBLE
        }
        position()
        log("render buffer=$buffer anchor=$anchorSource caret=${caretScreen?.joinToString()}")
    }

    /** Below the caret line; above it near the bottom; docked at the bottom with no geometry. */
    private fun position() {
        val root = content ?: return
        val p = panel ?: return
        p.post {
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            val margin = 8 * ims.resources.displayMetrics.density
            val caret = caretScreen
            if (caret == null) {
                p.x = margin
                p.y = root.height - p.height - margin * 6
            } else {
                val x = caret[0] - loc[0]
                val below = caret[1] - loc[1] + margin
                p.x = x.coerceIn(margin, (root.width - p.width - margin).coerceAtLeast(margin))
                p.y = if (below + p.height < root.height - margin * 6) below else below - p.height - margin * 6
            }
            ims.window?.window?.decorView?.requestLayout()
            log("panel at ${p.x},${p.y} size ${p.width}x${p.height} root ${root.width}x${root.height} rootLoc ${loc.joinToString()}")
        }
    }

    private fun setOtherChildrenVisible(visible: Boolean) {
        val root = content ?: return
        log("children: " + (0 until root.childCount).joinToString { i ->
            val c = root.getChildAt(i); "${c.javaClass.simpleName}(vis=${c.visibility}, ${c.width}x${c.height})"
        } + " -> visible=$visible")
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            // INVISIBLE, not GONE: the IME window wraps its content, so GONE shrinks it to a strip at the
            // bottom and the caret-anchored panel would fall outside it.
            if (child !== panel) child.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun log(msg: String) = Log.i("ZizaiS1", msg)

    @Suppress("unused")
    private fun RectF.str() = "[$left,$top,$right,$bottom]"
}
