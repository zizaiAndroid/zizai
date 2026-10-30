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

import android.content.Context
import android.content.SharedPreferences
import io.zizai.han.parse.Fuzzy
import io.zizai.han.parse.PinyinScheme
import io.zizai.zhtext.PunctuationLocale
import io.zizai.zhtext.ZhScript

/**
 * Zizai's own settings, kept apart from upstream's `AppPrefs` so merges never conflict
 * (docs/IMPLEMENTATION_PLAN.md P1-17). Reads are cheap and always current, so changes apply without a
 * restart.
 */
class ZizaiPrefs private constructor(private val sp: SharedPreferences) {
    /** Script for dictated Chinese text. Simplified by default (Simplified-first, §12). */
    var outputScript: ZhScript
        get() = enumOr(KEY_OUTPUT_SCRIPT, ZhScript.SIMPLIFIED)
        set(v) = sp.edit().putString(KEY_OUTPUT_SCRIPT, v.name).apply()

    val punctuationLocale: PunctuationLocale
        get() = when (outputScript) {
            ZhScript.TAIWAN, ZhScript.TAIWAN_PHRASES -> PunctuationLocale.TAIWAN
            ZhScript.HONG_KONG -> PunctuationLocale.HONG_KONG
            else -> PunctuationLocale.MAINLAND
        }

    /** Space between Chinese and Latin text in dictation. Off by default. */
    var cjkLatinSpacing: Boolean
        get() = sp.getBoolean(KEY_CJK_SPACING, false)
        set(v) = sp.edit().putBoolean(KEY_CJK_SPACING, v).apply()

    var fullWidthPunctuation: Boolean
        get() = sp.getBoolean(KEY_FULL_WIDTH, true)
        set(v) = sp.edit().putBoolean(KEY_FULL_WIDTH, v).apply()

    /** Candidates per page in the hardware panel, 5–10. */
    var pageSize: Int
        get() = sp.getInt(KEY_PAGE_SIZE, 9).coerceIn(5, 10)
        set(v) = sp.edit().putInt(KEY_PAGE_SIZE, v.coerceIn(5, 10)).apply()

    var hardwareMode: HardwareModeSetting
        get() = enumOr(KEY_HARDWARE_MODE, HardwareModeSetting.AUTO)
        set(v) = sp.edit().putString(KEY_HARDWARE_MODE, v.name).apply()

    /** Full Pinyin or a double-Pinyin scheme (P2-03). */
    var scheme: PinyinScheme
        get() = enumOr(KEY_SCHEME, PinyinScheme.QUANPIN)
        set(v) = sp.edit().putString(KEY_SCHEME, v.name).apply()

    /** Fuzzy Pinyin pairs that are on; none by default. */
    var fuzzy: Set<Fuzzy>
        get() = sp.getStringSet(KEY_FUZZY, emptySet()).orEmpty()
            .mapNotNull { runCatching { Fuzzy.valueOf(it) }.getOrNull() }.toSet()
        set(v) = sp.edit().putStringSet(KEY_FUZZY, v.map { it.name }.toSet()).apply()

    /** LLM homophone proofreading of dictation (P2-08): off by default, and with no default model. */
    var proofreadEnabled: Boolean
        get() = sp.getBoolean(KEY_PROOFREAD, false)
        set(v) = sp.edit().putBoolean(KEY_PROOFREAD, v).apply()

    var proofreadProvider: String
        get() = sp.getString(KEY_PROOFREAD_PROVIDER, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PROOFREAD_PROVIDER, v).apply()

    var proofreadModel: String
        get() = sp.getString(KEY_PROOFREAD_MODEL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_PROOFREAD_MODEL, v).apply()

    /** Whether the first-hardware-keyboard card has been shown. */
    var hardwareOnboardingShown: Boolean
        get() = sp.getBoolean(KEY_HW_ONBOARDING, false)
        set(v) = sp.edit().putBoolean(KEY_HW_ONBOARDING, v).apply()

    private inline fun <reified T : Enum<T>> enumOr(key: String, default: T): T =
        sp.getString(key, null)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    enum class HardwareModeSetting { AUTO, TOUCH, HARDWARE }

    companion object {
        private const val KEY_OUTPUT_SCRIPT = "output_script"
        private const val KEY_CJK_SPACING = "cjk_latin_spacing"
        private const val KEY_FULL_WIDTH = "full_width_punctuation"
        private const val KEY_PAGE_SIZE = "page_size"
        private const val KEY_HARDWARE_MODE = "hardware_mode"
        private const val KEY_HW_ONBOARDING = "hardware_onboarding_shown"
        private const val KEY_SCHEME = "pinyin_scheme"
        private const val KEY_FUZZY = "fuzzy_pinyin"
        private const val KEY_PROOFREAD = "proofread_enabled"
        private const val KEY_PROOFREAD_PROVIDER = "proofread_provider"
        private const val KEY_PROOFREAD_MODEL = "proofread_model"

        @Volatile private var instance: ZizaiPrefs? = null

        fun get(context: Context): ZizaiPrefs = instance ?: synchronized(this) {
            instance ?: ZizaiPrefs(context.applicationContext.getSharedPreferences("zizai", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
