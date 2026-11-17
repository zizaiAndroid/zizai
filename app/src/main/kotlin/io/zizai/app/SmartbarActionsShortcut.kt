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

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.keyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.compose.LocalPreviewFieldController
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.zizai.dictate.ProviderActions
import org.florisboard.lib.compose.stringRes

/**
 * Two more ways into the Smartbar actions editor, which upstream only opens from the last tile of the
 * ⋯ grid: holding ⋯ itself, and an entry on the Smartbar settings screen that opens the keyboard in the
 * preview field with the editor already up.
 */
object SmartbarActionsShortcut {
    /** Set by the settings entry; the keyboard opens the editor when it next starts in a field. */
    @Volatile
    private var pending = false

    /** From the Smartbar button's long press: holding ⋯ opens the editor. */
    fun onLongPress(action: QuickAction, context: Context): Boolean {
        if (action.keyData().code != KeyCode.TOGGLE_ACTIONS_OVERFLOW) return false
        open(context)
        return true
    }

    fun open(context: Context) {
        ProviderActions.sync(context)
        val keyboardManager by context.keyboardManager()
        keyboardManager.activeState.isActionsOverflowVisible = false
        keyboardManager.activeState.isActionsEditorVisible = true
    }

    /**
     * From the keyboard's start in a field. Posted, because upstream's start in a field closes the
     * editor right after this hook runs (EditorInstance.handleStartInputView).
     */
    fun consumePending(context: Context) {
        if (!pending) return
        pending = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ open(context) }, 150)
    }

    @Composable
    fun SettingsEntry() {
        val preview = LocalPreviewFieldController.current
        val softKeyboard = LocalSoftwareKeyboardController.current
        Preference(
            icon = Icons.Default.Tune,
            title = stringRes(R.string.zizai__smartbar_buttons__title),
            summary = stringRes(R.string.zizai__smartbar_buttons__summary),
            onClick = {
                pending = true
                preview?.isVisible = true
                runCatching { preview?.focusRequester?.requestFocus() }
                softKeyboard?.show()
            },
        )
    }
}
