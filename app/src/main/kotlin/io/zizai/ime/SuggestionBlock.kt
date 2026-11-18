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

import dev.patrickgold.florisboard.ime.editor.InputAttributes

/**
 * Which fields that set `TYPE_TEXT_FLAG_NO_SUGGESTIONS` Zizai may still suggest and autocorrect in.
 *
 * Zizai ignores that flag by default: Gemini's prompt box sets it, and so do plenty of other apps on
 * ordinary prose, which left them with no completions and no autocorrect at all. Upstream already
 * keeps password fields out of reach whatever the setting says. Zizai keeps email and web address
 * fields that ask for no suggestions out of reach too, because an autocorrect that "fixes" part of
 * an address breaks it, and those are the fields where the flag is usually meant.
 */
object SuggestionBlock {
    fun mayOverrule(variation: InputAttributes.Variation): Boolean = when (variation) {
        InputAttributes.Variation.EMAIL_ADDRESS,
        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
        InputAttributes.Variation.URI,
        -> false
        else -> true
    }
}
