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

import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider

/** A Han session candidate on the Smartbar strip; [index] counts across pages. A tap goes back to the session. */
data class HanSuggestionCandidate(override val text: CharSequence, val index: Int) : SuggestionCandidate {
    override val secondaryText: CharSequence? = null
    override val confidence: Double = 1.0
    override val isEligibleForAutoCommit: Boolean = false
    override val isEligibleForUserRemoval: Boolean = false
    override val icon: ImageVector? = null
    override val sourceProvider: SuggestionProvider? = null

    companion object {
        /** The index of the 改 chip, which starts a flip instead of picking a candidate. */
        const val FLIP_CHIP = -1
    }
}
