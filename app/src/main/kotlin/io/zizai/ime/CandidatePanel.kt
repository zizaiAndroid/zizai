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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import io.zizai.han.session.HanUiState
import io.zizai.han.session.SessionMode

/**
 * The floating candidate panel for hardware mode (docs/ARCHITECTURE.md §6.3): the preedit, a numbered
 * page of candidates with the highlight, the page and tone-filter indicators, or the per-tone grid.
 * A single TextView with spans keeps layout cheap on every keystroke.
 */
class CandidatePanel(context: Context) : TextView(context) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val fg = if (night) Color.parseColor("#ECEFF1") else Color.parseColor("#1B1F23")
    private val dim = if (night) Color.parseColor("#90A4AE") else Color.parseColor("#6A737D")
    private val accent = Color.parseColor("#0E7C66")
    private val highlightBg = if (night) Color.parseColor("#2E5E54") else Color.parseColor("#CDEDE6")

    init {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) }
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(12f).toInt(), dp(8f).toInt())
        setLineSpacing(dp(2f), 1f)
        background = GradientDrawable().apply {
            setColor(if (night) Color.parseColor("#F2263238") else Color.parseColor("#F7FFFFFF"))
            setStroke(dp(1f).toInt(), if (night) Color.parseColor("#37474F") else Color.parseColor("#D0D7DE"))
            cornerRadius = dp(10f)
        }
        elevation = dp(6f)
        visibility = View.GONE
    }

    fun render(state: HanUiState) {
        if (state.mode == SessionMode.IDLE) {
            visibility = View.GONE
            return
        }
        val sb = SpannableStringBuilder()
        // Header: the preedit (or 改字 while flipping), then page and tone filter.
        val header = if (state.mode == SessionMode.RECONVERTING) "改字" else state.preedit
        sb.append(header, ForegroundColorSpan(accent), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val info = buildString {
            append("   ")
            if (state.hasPreviousPage || state.hasNextPage) {
                append(if (state.hasPreviousPage) "◂ " else "  ")
                append(state.page + 1)
                append(if (state.hasNextPage) " ▸" else "  ")
            }
            if (state.toneFilter != 0) append("   声调 ").append(TONE_NAMES[state.toneFilter])
        }
        sb.append(info, ForegroundColorSpan(dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append('\n')

        if (state.mode == SessionMode.EXPANDED) {
            state.grid.forEachIndexed { r, row ->
                sb.append(row.label.padEnd(6), ForegroundColorSpan(dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Show a window of the row around the selected column.
                val col = if (r == state.gridRow) state.gridColumn else 0
                val from = (col - GRID_WINDOW / 2).coerceIn(0, maxOf(0, row.characters.size - GRID_WINDOW))
                if (from > 0) sb.append("… ")
                row.characters.subList(from, minOf(row.characters.size, from + GRID_WINDOW)).forEachIndexed { i, ch ->
                    val start = sb.length
                    sb.append(ch)
                    if (r == state.gridRow && from + i == state.gridColumn) {
                        sb.setSpan(BackgroundColorSpan(highlightBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    sb.append(' ')
                }
                if (from + GRID_WINDOW < row.characters.size) sb.append("…")
                if (r < state.grid.size - 1) sb.append('\n')
            }
        } else {
            state.candidates.forEachIndexed { i, c ->
                val label = if (i == 9) "0" else "${i + 1}"
                sb.append(label, ForegroundColorSpan(dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val start = sb.length
                sb.append(c.text)
                if (i == state.highlighted) {
                    sb.setSpan(BackgroundColorSpan(highlightBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (i < state.candidates.size - 1) sb.append("  ")
            }
            if (state.candidates.isEmpty()) sb.append("—", ForegroundColorSpan(dim), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = sb
        contentDescription = state.candidates.getOrNull(state.highlighted)?.text
        visibility = View.VISIBLE
    }

    private companion object {
        val TONE_NAMES = arrayOf("全部", "1 ˉ", "2 ˊ", "3 ˇ", "4 ˋ", "轻声")
        const val GRID_WINDOW = 16
    }
}

/** The 中/英 status pill, with a recording dot while dictating. Tapping it toggles the mode. */
class StatusPill(context: Context) : TextView(context) {
    init {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics) }
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#CC0E7C66"))
            cornerRadius = dp(14f)
        }
        elevation = dp(4f)
        visibility = View.GONE
    }

    fun render(chinese: Boolean, recording: Boolean) {
        text = buildString {
            append(if (chinese) "中" else "英")
            if (recording) append("  ● 录音")
        }
    }
}
