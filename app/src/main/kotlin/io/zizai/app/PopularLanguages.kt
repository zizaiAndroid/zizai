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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.stringRes

/**
 * Finding and adding a language. Upstream calls languages "subtypes", lists every preset in one
 * alphabetical dialog with no search, and suggests only the phone's own languages, which put 郑码 next
 * to Pinyin and left Arabic a long scroll away. Here the most-used input methods come first, every
 * language is one search away, and the less common shape-based methods wait behind "Show more".
 */
object PopularLanguages {
    /** Language pack entries few people use, behind "Show more": 郑码, 嘸蝦米 and the German Neo layout. */
    private val LESS_COMMON = setOf("zh_CN_zhengma", "zh_TW_boshiamy", "de_DE_neobone")

    fun isLessCommon(componentId: String) = componentId in LESS_COMMON

    /** The popular input methods: Chinese, Cantonese, English, Korean, Japanese, then the world's big ones. */
    private val PRESET_TAGS = listOf(
        "zh-CN-pinyin", "yue-HK-jyutping", "zh-TW-cangjielarge", "en-US", "ko", "ja-JP-jis",
        "es-ES", "fr-FR", "de-DE", "pt-BR", "ru", "ar", "hi-IN", "id", "vi-VN",
    )

    private fun popular(all: List<SubtypePreset>): List<SubtypePreset> =
        PRESET_TAGS.mapNotNull { tag -> all.find { it.locale.languageTag().equals(tag, ignoreCase = true) } }

    /** Suggested on the add-language screen, after the phone's own languages. */
    fun addPopularPresets(presets: MutableList<SubtypePreset>, all: List<SubtypePreset>) {
        for (preset in popular(all).take(6)) if (preset !in presets) presets += preset
    }

    /** "+ Add a language", under the user's languages. */
    @Composable
    fun AddLanguageRow(onClick: () -> Unit) {
        Preference(
            icon = Icons.Default.Add,
            title = stringRes(R.string.zizai__languages__add),
            summary = stringRes(R.string.zizai__languages__add_summary),
            onClick = onClick,
        )
    }

    /**
     * The language list: a search box over every language (by its name in English or in its own script,
     * or its code), popular ones first while nothing is typed. [onPick] gets the chosen preset.
     */
    @Composable
    fun LanguagePicker(presets: List<SubtypePreset>, nativeNames: Boolean, onPick: (SubtypePreset) -> Unit) {
        var query by rememberSaveable { mutableStateOf("") }
        fun name(p: SubtypePreset) = if (nativeNames) p.locale.displayName(p.locale) else p.locale.displayName()
        val popular = remember(presets) { popular(presets) }
        val matches = remember(presets, query) {
            // Pinyin composition in this very field arrives as "a'r'a'b"; the apostrophes aren't the query.
            val q = query.filterNot { it == '\'' || it.isWhitespace() }
            if (q.isEmpty()) presets else presets.filter { p -> searchNames(p).any { it.contains(q, ignoreCase = true) } }
        }
        Column {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringRes(R.string.zizai__languages__search)) },
            )
            HorizontalDivider()
            val listState = rememberLazyListState()
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp), state = listState) {
                @Composable
                fun Row(p: SubtypePreset) = ListItem(
                    modifier = Modifier.clickable { onPick(p) },
                    headlineContent = { Text(name(p)) },
                    supportingContent = { Text(p.preferred.characters.componentId) },
                    colors = ListItemDefaults.colors(containerColor = AlertDialogDefaults.containerColor),
                )
                if (query.isBlank()) {
                    item { Header(stringRes(R.string.zizai__languages__popular)) }
                    items(popular) { Row(it) }
                    item { Header(stringRes(R.string.zizai__languages__all)) }
                }
                items(matches) { Row(it) }
                if (matches.isEmpty()) item {
                    Text(
                        stringRes(R.string.zizai__languages__none),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }

    /** A preset's names to search: in the phone's language, its own, Chinese (阿拉伯语, 阿拉伯語), and its code. */
    private fun searchNames(p: SubtypePreset): List<String> {
        val base = p.locale.base
        return listOf(
            p.locale.displayName(), p.locale.displayName(p.locale), p.locale.languageTag(),
            base.getDisplayLanguage(java.util.Locale.SIMPLIFIED_CHINESE),
            base.getDisplayLanguage(java.util.Locale.TRADITIONAL_CHINESE),
        ).map { it.filterNot(Char::isWhitespace) }
    }

    @Composable
    private fun Header(text: String) = Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )

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
