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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout

/**
 * Hardware mode (ADR-5, docs/IMPLEMENTATION_PLAN.md P1-13): the IME window stays shown, so the microphone
 * works (spike S2), but the keyboard is hidden and the app keeps its full height. Only the candidate
 * panel, placed at the caret (spike S1), and the status pill take touches.
 */
class HardwareMode(private val ims: InputMethodService) {
    var active = false
        private set

    private var content: ViewGroup? = null
    lateinit var panel: CandidatePanel
        private set
    lateinit var pill: StatusPill
        private set
    private var caretScreen: FloatArray? = null

    fun attach(content: ViewGroup) {
        this.content = content
        panel = CandidatePanel(ims)
        pill = StatusPill(ims)
        content.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ))
        content.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(12f), dp(12f)) })
    }

    fun enter() {
        if (active || content == null) return
        active = true
        setKeyboardVisible(false)
        pill.visibility = View.VISIBLE
        ims.currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        if (!ims.isInputViewShown) ims.requestShowSelf(0)
    }

    fun exit() {
        if (!active) return
        active = false
        panel.visibility = View.GONE
        pill.visibility = View.GONE
        setKeyboardVisible(true)
        ims.currentInputConnection?.requestCursorUpdates(0)
    }

    /** The app keeps its full height; only the panel and the pill are touchable. */
    fun computeInsets(outInsets: InputMethodService.Insets) {
        val root = content ?: return
        outInsets.contentTopInsets = root.height
        outInsets.visibleTopInsets = root.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        for (v in listOf(panel, pill)) {
            if (v.visibility != View.VISIBLE) continue
            outInsets.touchableRegion.union(android.graphics.Rect(v.left + v.translationX.toInt(), v.top + v.translationY.toInt(),
                v.right + v.translationX.toInt(), v.bottom + v.translationY.toInt()))
        }
    }

    fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val pts = when {
            !info.insertionMarkerHorizontal.isNaN() -> floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerBottom)
            info.composingTextStart >= 0 && info.composingText != null && info.composingText.isNotEmpty() ->
                info.getCharacterBounds(info.composingTextStart + info.composingText.length - 1)
                    ?.let { floatArrayOf(it.right, it.bottom) }
            else -> null
        }
        caretScreen = pts?.also { info.matrix.mapPoints(it) }
        position()
    }

    /** Below the caret line; above it near the bottom; docked at the bottom with no geometry. */
    fun position() {
        val root = content ?: return
        val maxWidth = root.width - 2 * dp(8f)
        if (maxWidth > 0 && panel.maxWidth != maxWidth) panel.maxWidth = maxWidth
        panel.post {
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            val margin = dp(8f).toFloat()
            val caret = caretScreen
            val bottomReserve = margin * 6
            if (caret == null) {
                panel.translationX = margin
                panel.translationY = root.height - panel.height - bottomReserve
            } else {
                val x = caret[0] - loc[0]
                val below = caret[1] - loc[1] + margin
                panel.translationX = x.coerceIn(margin, (root.width - panel.width - margin).coerceAtLeast(margin))
                panel.translationY = if (below + panel.height < root.height - bottomReserve) below
                else (below - panel.height - margin * 5).coerceAtLeast(margin)
            }
            ims.window?.window?.decorView?.requestLayout()
        }
    }

    private fun setKeyboardVisible(visible: Boolean) {
        val root = content ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === panel || child === pill) continue
            // INVISIBLE, not GONE: the IME window wraps its content, so GONE would shrink it to a strip at
            // the bottom and the caret-anchored panel would fall outside it (spike S1).
            child.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ims.resources.displayMetrics).toInt()
}
