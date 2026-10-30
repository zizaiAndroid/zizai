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

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.zizai.ZizaiPrefs
import org.florisboard.lib.compose.stringRes

/**
 * Hardware-keyboard settings (docs/IMPLEMENTATION_PLAN.md P2-04): the compact preset, the keys bound to
 * flip, push-to-talk and the next suspect ("press the key to bind"), and apps Zizai stays out of.
 */
@Composable
fun HardwareKeyRows(prefs: ZizaiPrefs) {
    var compact by remember { mutableStateOf(prefs.compactKeyboard) }
    Preference(
        title = stringRes(R.string.zizai__compact__title),
        summary = stringRes(R.string.zizai__compact__summary),
        onClick = { compact = !compact; prefs.compactKeyboard = compact },
        trailing = { Switch(checked = compact, onCheckedChange = { compact = it; prefs.compactKeyboard = it }) },
    )

    var flip by remember { mutableStateOf(prefs.flipKey) }
    var ptt by remember { mutableStateOf(prefs.pushToTalkKey) }
    var suspect by remember { mutableStateOf(prefs.suspectKey) }
    KeyBindingRow(stringRes(R.string.zizai__bind__flip), flip) { flip = it; prefs.flipKey = it }
    KeyBindingRow(stringRes(R.string.zizai__bind__ptt), ptt) { ptt = it; prefs.pushToTalkKey = it }
    KeyBindingRow(stringRes(R.string.zizai__bind__suspect), suspect) { suspect = it; prefs.suspectKey = it }

    BypassAppsRow(prefs)
}

@Composable
private fun KeyBindingRow(title: String, keyCode: Int, onBind: (Int) -> Unit) {
    var capturing by remember { mutableStateOf(false) }
    Preference(title = title, summary = keyName(keyCode), onClick = { capturing = true })
    if (!capturing) return
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { capturing = false },
        confirmButton = { TextButton(onClick = { capturing = false }) { Text(stringRes(R.string.zizai__close)) } },
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        val native = e.nativeKeyEvent
                        // Back and Escape close the dialog rather than bind.
                        if (e.type != KeyEventType.KeyDown || native.keyCode == KeyEvent.KEYCODE_BACK ||
                            native.keyCode == KeyEvent.KEYCODE_ESCAPE
                        ) return@onPreviewKeyEvent false
                        onBind(native.keyCode)
                        capturing = false
                        true
                    },
            ) {
                Text(stringRes(R.string.zizai__bind__press))
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
    )
}

private fun keyName(keyCode: Int): String =
    KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
        .replaceFirstChar { it.uppercase() }

@Composable
private fun BypassAppsRow(prefs: ZizaiPrefs) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var bypassed by remember { mutableStateOf(prefs.bypassPackages) }
    val apps = remember(open) {
        if (!open) emptyList() else {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION") // the flags overload is API 33+, minSdk is 26
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
    }
    Preference(
        title = stringRes(R.string.zizai__bypass__title),
        summary = if (bypassed.isEmpty()) stringRes(R.string.zizai__bypass__none) else bypassed.sorted().joinToString(", "),
        onClick = { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringRes(R.string.zizai__close)) } },
        title = { Text(stringRes(R.string.zizai__bypass__title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((pkg, label) in apps) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            bypassed = if (pkg in bypassed) bypassed - pkg else bypassed + pkg
                            prefs.bypassPackages = bypassed
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pkg in bypassed, onCheckedChange = null)
                        Text(label)
                    }
                }
            }
        },
    )
}
