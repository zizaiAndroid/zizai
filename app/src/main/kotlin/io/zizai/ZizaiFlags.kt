/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai

import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.ime.text.key.KeyCode

/**
 * The v1 feature flags (ADR-8), set in `zizai.properties` and compiled into [BuildConfig].
 *
 * Upstream code asks these through one-line `ZIZAI-HOOK` checks, so turning a feature back on is a matter
 * of flipping its property and deleting its lines from `app/src/release/AndroidManifest.xml`.
 */
object ZizaiFlags {
    /** Dictate Cloud prepaid credit: upstream's backend and Play Billing. */
    const val cloud: Boolean = BuildConfig.ZIZAI_CLOUD

    /** The floating dictation button, which needs an AccessibilityService. */
    const val floatingButton: Boolean = BuildConfig.ZIZAI_FLOATING_BUTTON

    /** On-device translation (Bergamot). */
    const val translate: Boolean = BuildConfig.ZIZAI_TRANSLATE

    /** GIF search (KLIPY). */
    const val gif: Boolean = BuildConfig.ZIZAI_GIF

    /**
     * The Wear OS companion. Not a property: v1 doesn't upload the `:wear` app, and the phone-side wear
     * services are removed by the release manifest, so its settings would lead nowhere.
     */
    const val wear: Boolean = false

    /**
     * Upstream's release notes: the "What's new" tours, the changelog dialog and the changelog nudge all
     * describe Dictate Keyboard's releases, not Zizai's. Off until Zizai has release notes of its own.
     */
    const val upstreamReleaseNotes: Boolean = false

    /**
     * Upstream's donate nudge opens the upstream developer's PayPal page. Never shown in Zizai: the money
     * would go to someone else, and Play's payments policy doesn't allow external payment links.
     */
    const val donations: Boolean = false

    /** Zizai's own Google Play listing, for the "rate the app" nudge and the stats screen. */
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=io.zizai.keyboard"

    /** The package name backups must come from to be restored. */
    const val PACKAGE_NAME = "io.zizai.keyboard"

    /** Drops the Smartbar actions of features that are switched off; [codeOf] reads an action's key code. */
    fun <T> filterQuickActions(actions: List<T>, codeOf: (T) -> Int): List<T> = actions.filterNot {
        val code = codeOf(it)
        (!translate && code == KeyCode.TRANSLATE) || (!gif && code == KeyCode.IME_UI_MODE_GIF)
    }

    /** Whether a settings-search result points at something that is shown. */
    fun allowsSearchEntry(route: Any?, anchor: String?): Boolean =
        allowsRoute(route) && (upstreamReleaseNotes || anchor != "about__whats_new__title")

    /** Whether a settings route belongs to a feature that is switched on. */
    fun allowsRoute(route: Any?): Boolean = when (route) {
        Routes.Settings.DictateCloud -> cloud
        Routes.Settings.DictateFloatingButton, Routes.Settings.DictateFloatingButtonApps -> floatingButton
        Routes.Settings.Translation -> translate
        Routes.Settings.DictateWear -> wear
        else -> true
    }
}
