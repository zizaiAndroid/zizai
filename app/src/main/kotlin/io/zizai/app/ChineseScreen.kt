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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.zizai.ZizaiPrefs
import io.zizai.han.parse.Fuzzy
import io.zizai.han.parse.PinyinScheme
import io.zizai.ime.HanPackManager
import kotlinx.coroutines.launch
import io.zizai.zhtext.ZhScript
import org.florisboard.lib.compose.stringRes

/**
 * The 中文 / Chinese settings screen (docs/IMPLEMENTATION_PLAN.md P1-17). Backed by [ZizaiPrefs], which
 * the keyboard reads on every use, so changes apply without a restart.
 */
@Composable
fun ChineseScreen() = FlorisScreen {
    title = stringRes(R.string.zizai__chinese__title)

    content {
        val prefs = ZizaiPrefs.get(LocalContext.current)

        val scripts = listOf(
            ZhScript.SIMPLIFIED to stringRes(R.string.zizai__output_script__simplified),
            ZhScript.TRADITIONAL to stringRes(R.string.zizai__output_script__traditional),
            ZhScript.TAIWAN to stringRes(R.string.zizai__output_script__taiwan),
            ZhScript.TAIWAN_PHRASES to stringRes(R.string.zizai__output_script__taiwan_phrases),
            ZhScript.HONG_KONG to stringRes(R.string.zizai__output_script__hong_kong),
            ZhScript.AS_IS to stringRes(R.string.zizai__output_script__as_is),
        )
        var script by remember { mutableStateOf(prefs.outputScript) }
        ChoicePreference(stringRes(R.string.zizai__output_script__title), scripts, script) {
            script = it
            prefs.outputScript = it
        }

        val modes = listOf(
            ZizaiPrefs.HardwareModeSetting.AUTO to stringRes(R.string.zizai__hardware_mode__auto),
            ZizaiPrefs.HardwareModeSetting.TOUCH to stringRes(R.string.zizai__hardware_mode__touch),
            ZizaiPrefs.HardwareModeSetting.HARDWARE to stringRes(R.string.zizai__hardware_mode__hardware),
        )
        var mode by remember { mutableStateOf(prefs.hardwareMode) }
        ChoicePreference(stringRes(R.string.zizai__hardware_mode__title), modes, mode) {
            mode = it
            prefs.hardwareMode = it
        }

        val schemes = listOf(
            PinyinScheme.QUANPIN to stringRes(R.string.zizai__scheme__quanpin),
            PinyinScheme.XIAOHE to stringRes(R.string.zizai__scheme__xiaohe),
            PinyinScheme.ZIRANMA to stringRes(R.string.zizai__scheme__ziranma),
            PinyinScheme.MICROSOFT to stringRes(R.string.zizai__scheme__microsoft),
        )
        var scheme by remember { mutableStateOf(prefs.scheme) }
        ChoicePreference(stringRes(R.string.zizai__scheme__title), schemes, scheme) {
            scheme = it
            prefs.scheme = it
        }

        FuzzyPreference(prefs)

        var pageSize by remember { mutableStateOf(prefs.pageSize) }
        ChoicePreference(stringRes(R.string.zizai__page_size__title), (5..10).map { it to "$it" }, pageSize) {
            pageSize = it
            prefs.pageSize = it
        }

        var fullWidth by remember { mutableStateOf(prefs.fullWidthPunctuation) }
        SwitchRow(stringRes(R.string.zizai__full_width__title), stringRes(R.string.zizai__full_width__summary), fullWidth) {
            fullWidth = it
            prefs.fullWidthPunctuation = it
        }

        var spacing by remember { mutableStateOf(prefs.cjkLatinSpacing) }
        SwitchRow(stringRes(R.string.zizai__cjk_spacing__title), stringRes(R.string.zizai__cjk_spacing__summary), spacing) {
            spacing = it
            prefs.cjkLatinSpacing = it
        }

        HardwareKeyRows(prefs)

        ModernPackRow()

        ProofreadRows(prefs)

        val navController = LocalNavController.current
        Preference(
            title = stringRes(R.string.zizai__words__title),
            summary = stringRes(R.string.zizai__words__summary),
            onClick = { navController.navigate(Routes.Settings.ChineseWords) },
        )

        var cheatSheet by remember { mutableStateOf(false) }
        Preference(
            title = stringRes(R.string.zizai__cheat_sheet__title),
            summary = stringRes(R.string.zizai__cheat_sheet__summary),
            onClick = { cheatSheet = true },
        )
        if (cheatSheet) {
            AlertDialog(
                onDismissRequest = { cheatSheet = false },
                confirmButton = { TextButton(onClick = { cheatSheet = false }) { Text(stringRes(R.string.zizai__close)) } },
                title = { Text(stringRes(R.string.zizai__cheat_sheet__title)) },
                text = { Text(stringRes(R.string.zizai__cheat_sheet)) },
            )
        }
    }
}

/** The fuzzy Pinyin pairs, each on or off (P2-03). */
@Composable
private fun FuzzyPreference(prefs: ZizaiPrefs) {
    var open by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(prefs.fuzzy) }
    val labels = mapOf(
        Fuzzy.Z_ZH to "z = zh", Fuzzy.C_CH to "c = ch", Fuzzy.S_SH to "s = sh", Fuzzy.N_L to "n = l",
        Fuzzy.R_L to "r = l", Fuzzy.F_H to "f = h", Fuzzy.AN_ANG to "an = ang", Fuzzy.EN_ENG to "en = eng",
        Fuzzy.IN_ING to "in = ing", Fuzzy.IAN_IANG to "ian = iang", Fuzzy.UAN_UANG to "uan = uang",
    )
    Preference(
        title = stringRes(R.string.zizai__fuzzy__title),
        summary = if (on.isEmpty()) stringRes(R.string.zizai__fuzzy__none)
        else Fuzzy.entries.filter { it in on }.joinToString(", ") { labels.getValue(it) },
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringRes(R.string.zizai__close)) } },
            title = { Text(stringRes(R.string.zizai__fuzzy__title)) },
            text = {
                Column {
                    for (f in Fuzzy.entries) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                on = if (f in on) on - f else on + f
                                prefs.fuzzy = on
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = f in on, onCheckedChange = { checked ->
                                on = if (checked) on + f else on - f
                                prefs.fuzzy = on
                            })
                            Text(labels.getValue(f))
                        }
                    }
                }
            },
        )
    }
}

/**
 * LLM homophone proofreading (P2-08): the switch stays disabled until a model is picked from the user's
 * own configured providers, and says what it sends where.
 */
@Composable
private fun ProofreadRows(prefs: ZizaiPrefs) {
    var enabled by remember { mutableStateOf(prefs.proofreadEnabled) }
    var provider by remember { mutableStateOf(prefs.proofreadProvider) }
    var model by remember { mutableStateOf(prefs.proofreadModel) }
    var picking by remember { mutableStateOf(false) }
    val choices = remember { io.zizai.dictate.Proofreader.choices() }
    val chosen = choices.firstOrNull { it.providerId == provider && it.model == model }

    Preference(
        title = stringRes(R.string.zizai__proofread__model),
        summary = chosen?.let { "${it.label} · ${it.model}" }
            ?: if (choices.isEmpty()) stringRes(R.string.zizai__proofread__no_providers) else stringRes(R.string.zizai__proofread__pick),
        onClick = { if (choices.isNotEmpty()) picking = true },
    )
    Preference(
        title = stringRes(R.string.zizai__proofread__title),
        summary = if (chosen == null) stringRes(R.string.zizai__proofread__needs_model) else stringRes(R.string.zizai__proofread__summary),
        enabledIf = { chosen != null },
        onClick = { if (chosen != null) { enabled = !enabled; prefs.proofreadEnabled = enabled } },
        trailing = {
            Switch(
                checked = enabled && chosen != null,
                enabled = chosen != null,
                onCheckedChange = { enabled = it; prefs.proofreadEnabled = it },
            )
        },
    )
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringRes(R.string.zizai__close)) } },
            title = { Text(stringRes(R.string.zizai__proofread__model)) },
            text = {
                Column {
                    for (c in choices) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                provider = c.providerId; model = c.model
                                prefs.proofreadProvider = c.providerId; prefs.proofreadModel = c.model
                                picking = false
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == chosen, onClick = null)
                            Text("${c.label} · ${c.model}")
                        }
                    }
                }
            },
        )
    }
}

/** Downloads or deletes the modern vocabulary pack (P1-08). */
@Composable
private fun ModernPackRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(HanPackManager.isInstalled(context)) }
    var failed by remember { mutableStateOf(false) }
    val progress by HanPackManager.progress.collectAsState()
    val sizeMb = "%.1f".format(HanPackManager.SIZE_BYTES / 1_000_000.0)
    val summary = when {
        progress != null -> stringRes(R.string.zizai__modern_pack__downloading, "progress" to "${progress}")
        failed -> stringRes(R.string.zizai__modern_pack__failed)
        installed -> stringRes(R.string.zizai__modern_pack__installed)
        else -> stringRes(R.string.zizai__modern_pack__summary, "size" to sizeMb)
    }
    SwitchRow(stringRes(R.string.zizai__modern_pack__title), summary, installed || progress != null) { on ->
        if (progress != null) return@SwitchRow
        failed = false
        if (on) {
            scope.launch {
                failed = runCatching { HanPackManager.install(context) }.isFailure
                installed = HanPackManager.isInstalled(context)
            }
        } else {
            HanPackManager.delete(context)
            installed = HanPackManager.isInstalled(context)
        }
    }
}

@Composable
private fun <T> ChoicePreference(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Preference(
        title = title,
        summary = options.firstOrNull { it.first == selected }?.second,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringRes(R.string.zizai__close)) } },
            title = { Text(title) },
            text = {
                Column {
                    for ((value, label) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value); open = false }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = { onSelect(value); open = false })
                            Text(label)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Preference(
        title = title,
        summary = summary,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
