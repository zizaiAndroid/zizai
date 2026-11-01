/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.app

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.stringRes

/**
 * The input methods most people want, shown first; the rest stay one tap away. Upstream lists every
 * language pack entry and suggests only the phone's own languages, which put 郑码 next to Pinyin.
 */
object PopularLanguages {
    /** Language pack entries few people use, behind "Show more": 郑码, 嘸蝦米 and the German Neo layout. */
    private val LESS_COMMON = setOf("zh_CN_zhengma", "zh_TW_boshiamy", "de_DE_neobone")

    fun isLessCommon(componentId: String) = componentId in LESS_COMMON

    /** Suggested when adding a language, after the phone's own: Chinese, Cantonese, English, Korean, Japanese. */
    private val PRESET_TAGS = listOf("zh-CN-pinyin", "yue-HK-jyutping", "zh-TW-cangjielarge", "en-US", "ko", "ja-JP-jis")

    fun addPopularPresets(presets: MutableList<SubtypePreset>, all: List<SubtypePreset>) {
        for (tag in PRESET_TAGS) {
            val preset = all.find { it.locale.languageTag().equals(tag, ignoreCase = true) } ?: continue
            if (preset !in presets) presets += preset
        }
    }

    /** The language pack screen's "Show more input methods" / "Show fewer". */
    @Composable
    fun ShowMoreRow(showing: Boolean, onChange: (Boolean) -> Unit) {
        Preference(
            title = stringRes(if (showing) R.string.zizai__packs__show_fewer else R.string.zizai__packs__show_more),
            summary = if (showing) null else stringRes(R.string.zizai__packs__show_more_summary),
            onClick = { onChange(!showing) },
        )
    }
}
