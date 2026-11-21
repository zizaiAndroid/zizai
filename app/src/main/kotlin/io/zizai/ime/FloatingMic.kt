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

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.zizai.ZizaiPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.collectAsState
import org.florisboard.lib.compose.stringRes

/**
 * The dictation mic as a button twice its Smartbar size that floats over the screen. A tap does what
 * the Smartbar mic does (start, send, stop); a drag moves it anywhere on the screen, and it stays
 * where it was left, across fields and restarts.
 *
 * It is a popup window of the keyboard's, the same kind push-to-talk already draws its mic in, so it
 * can sit over the app as well as over the keys, and it is there exactly while the Smartbar is. It
 * takes over the Smartbar's sticky slot only while that slot holds the mic; the slot then stays empty
 * and the suggestions get its room. Hold-to-talk stays with the Smartbar mic: on a button that moves
 * when dragged, a hold and the start of a move cannot be told apart. The switch on the Smartbar
 * settings screen puts it back in the bar.
 */
object FloatingMic {
    private val enabledFlow = MutableStateFlow<Boolean?>(null)

    private fun enabled(context: Context): StateFlow<Boolean> {
        if (enabledFlow.value == null) enabledFlow.value = ZizaiPrefs.get(context).floatingMic
        @Suppress("UNCHECKED_CAST")
        return enabledFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun setEnabled(context: Context, on: Boolean) {
        ZizaiPrefs.get(context).floatingMic = on
        enabledFlow.value = on
    }

    /** The diameter: twice the mic's ~0.8 of a Smartbar row. */
    private val diameter: androidx.compose.ui.unit.Dp
        @Composable get() = FlorisImeSizing.smartbarHeight * 1.6f

    private val MARGIN = 12.dp

    /**
     * From the Smartbar's sticky slot: draws the floating mic instead when [action] is the mic and
     * floating is on, and answers whether it did, so the slot leaves its own button out.
     */
    @Composable
    fun takesOver(action: QuickAction?, evaluator: ComputingEvaluator): Boolean {
        val context = LocalContext.current
        val on by enabled(context).collectAsState()
        val mic = action as? QuickAction.InsertKey ?: return false
        if (!on || mic.data.code != KeyCode.IME_UI_MODE_DICTATE) return false
        Bubble(mic, evaluator)
        return true
    }

    /** The bubble's top-left in screen pixels, clamped so the whole button stays on the screen. */
    internal fun clamp(x: Int, y: Int, size: Int, screenW: Int, screenH: Int) = IntOffset(
        x.coerceIn(0, (screenW - size).coerceAtLeast(0)),
        y.coerceIn(0, (screenH - size).coerceAtLeast(0)),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @SuppressLint("ClickableViewAccessibility")
    @Composable
    private fun Bubble(mic: QuickAction.InsertKey, evaluator: ComputingEvaluator) {
        val context = LocalContext.current
        val view = LocalView.current
        val density = LocalDensity.current
        val zizaiPrefs = remember { ZizaiPrefs.get(context) }
        val sizePx = with(density) { diameter.roundToPx() }
        val marginPx = with(density) { MARGIN.roundToPx() }
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        // Screen position; null until the button is first moved, which leaves it at the default.
        var screenPos by remember {
            mutableStateOf(
                if (zizaiPrefs.floatingMicX < 0f || zizaiPrefs.floatingMicY < 0f) null
                else IntOffset((zizaiPrefs.floatingMicX * screenW).toInt(), (zizaiPrefs.floatingMicY * screenH).toInt()),
            )
        }
        val dictateState by DictateController.state.collectAsState()
        val icon = remember(evaluator, dictateState) { evaluator.computeImageVector(mic.data) }
        val prefs by FlorisPreferenceStore
        val accent = remember { prefs.theme.accentColor.get() }

        Popup(
            properties = PopupProperties(focusable = false, clippingEnabled = false),
            popupPositionProvider = remember(screenPos, sizePx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val origin = windowOriginOnScreen(view)
                        // Default: at the right, just above the Smartbar, over the app.
                        val pos = screenPos ?: clamp(
                            origin.x + anchorBounds.right - sizePx - marginPx,
                            origin.y + anchorBounds.top - sizePx - marginPx,
                            sizePx, screenW, screenH,
                        )
                        return IntOffset(pos.x - origin.x, pos.y - origin.y)
                    }
                }
            },
        ) {
            val slop = remember { ViewConfiguration.get(context).scaledTouchSlop }
            var downRaw by remember { mutableStateOf(IntOffset.Zero) }
            var downPos by remember { mutableStateOf(IntOffset.Zero) }
            var dragging by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(diameter)
                    .shadow(8.dp, CircleShape)
                    .background(accent, CircleShape)
                    .pointerInteropFilter { ev ->
                        val raw = IntOffset(ev.rawX.toInt(), ev.rawY.toInt())
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downRaw = raw
                                // Before the first move it sits at the default: the touch on the screen
                                // minus the touch's spot in the button gives where that is.
                                downPos = screenPos ?: IntOffset(raw.x - ev.x.toInt(), raw.y - ev.y.toInt())
                                dragging = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = raw.x - downRaw.x
                                val dy = raw.y - downRaw.y
                                if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                                if (dragging) screenPos = clamp(downPos.x + dx, downPos.y + dy, sizePx, screenW, screenH)
                            }
                            MotionEvent.ACTION_UP -> {
                                if (dragging) {
                                    screenPos?.let {
                                        zizaiPrefs.floatingMicX = it.x.toFloat() / screenW
                                        zizaiPrefs.floatingMicY = it.y.toFloat() / screenH
                                    }
                                } else {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    val keyboardManager by context.keyboardManager()
                                    keyboardManager.inputEventDispatcher.sendDownUp(mic.data)
                                }
                                dragging = false
                            }
                            MotionEvent.ACTION_CANCEL -> dragging = false
                        }
                        true
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(diameter * 0.5f))
                }
            }
        }
    }

    /** Where the keyboard window's (0, 0) is on the screen; popups are placed in window coordinates. */
    private fun windowOriginOnScreen(view: View): IntOffset {
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        view.getLocationOnScreen(onScreen)
        view.getLocationInWindow(inWindow)
        return IntOffset(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1])
    }

    /** "Floating mic button" on the Smartbar settings screen. */
    @Composable
    fun SettingsSwitch() {
        val context = LocalContext.current
        val on by enabled(context).collectAsState()
        Preference(
            title = stringRes(R.string.zizai__floating_mic__title),
            summary = stringRes(R.string.zizai__floating_mic__summary),
            onClick = { setEnabled(context, !on) },
            trailing = { Switch(checked = on, onCheckedChange = { setEnabled(context, it) }) },
        )
    }
}
