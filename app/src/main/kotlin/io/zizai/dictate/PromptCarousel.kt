/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.dictate

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the AI prompt card row where the user left it. The card last tapped, or the one nearest the
 * middle when a swipe comes to rest, is scrolled to the centre and stays there: the scroll position
 * lives here rather than in the composition, so it survives the row being hidden while Chinese
 * candidates take its line, a prompt being applied, or the keyboard closing.
 */
object PromptCarousel {
    /** Key of the live-prompt (voice) card; saved prompts use their database id. */
    const val LIVE = Int.MIN_VALUE

    val scroll = ScrollState(0)
    private var selected: Int? = null
    private var viewport: LayoutCoordinates? = null
    private val cards = HashMap<Int, LayoutCoordinates>()

    /** On the row, before `horizontalScroll`, so it measures the visible window. */
    fun viewport(): Modifier = Modifier.onGloballyPositioned { viewport = it }

    fun card(key: Int): Modifier = Modifier.onGloballyPositioned { cards[key] = it }

    /** Centres [key] and remembers it as the chosen card. */
    fun select(key: Int, scope: CoroutineScope) {
        selected = key
        scope.launch { target(key)?.let { scroll.animateScrollTo(it) } }
    }

    /** Re-centres the chosen card when the row comes back, and snaps after the user swipes. */
    @Composable
    fun Effects(keys: List<Int>) {
        LaunchedEffect(keys) {
            cards.keys.retainAll(keys.toSet())
            if (selected !in keys) selected = null
            awaitFrame()
            selected?.let { key -> target(key)?.let { scroll.scrollTo(it) } }
        }
        LaunchedEffect(Unit) {
            // Labels change width when the keyboard switches language (中 ↔ EN); keep the chosen card centred.
            snapshotFlow { scroll.maxValue }.collect {
                if (scroll.isScrollInProgress) return@collect
                awaitFrame()
                selected?.let { key -> target(key)?.let { scroll.scrollTo(it) } }
            }
        }
        LaunchedEffect(Unit) {
            var swiped = false
            launch {
                scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) swiped = true }
            }
            snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
                if (moving || !swiped) return@collect
                swiped = false
                val key = nearest() ?: return@collect
                selected = key
                target(key)?.let { scroll.animateScrollTo(it) }
            }
        }
    }

    /** Scroll offset that puts [key] in the middle, as far as the row's ends allow. */
    private fun target(key: Int): Int? {
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val card = cards[key]?.takeIf { it.isAttached } ?: return null
        val offset = scroll.value + center(card) - center(view)
        return offset.roundToInt().coerceIn(0, scroll.maxValue)
    }

    private fun nearest(): Int? {
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val middle = center(view)
        return cards.entries.filter { it.value.isAttached }.minByOrNull { abs(center(it.value) - middle) }?.key
    }

    private fun center(coordinates: LayoutCoordinates): Float =
        coordinates.positionInWindow().x + coordinates.size.width / 2f
}
