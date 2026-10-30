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

import android.content.Context
import dev.patrickgold.florisboard.dictate.DictationSink
import io.zizai.ZizaiPrefs
import io.zizai.ime.HanAssets
import io.zizai.zhtext.PunctuationLocale
import io.zizai.zhtext.ZhConverter
import io.zizai.zhtext.ZhScript
import io.zizai.zhtext.ZhText

/**
 * Cleans up dictated Chinese before it reaches the editor (docs/IMPLEMENTATION_PLAN.md P1-10): spaces
 * between Han characters go, ASCII punctuation after Chinese becomes full-width, and the text is
 * converted to the chosen script. Upstream's find-and-replace rules have already run on the text.
 *
 * The transform is a pure function of the text, so previews and the final text stay consistent: the
 * controller's `prevText` maps to exactly what this sink wrote before.
 */
class TranscriptPostProcessingSink(private val inner: DictationSink, private val transform: (String) -> String) :
    DictationSink by inner {

    override fun commitText(text: String, verify: Boolean): Boolean {
        val out = transform(text)
        return inner.commitText(out, verify).also { if (it) proofread(out) }
    }
    override fun deleteLastText(text: String) = inner.deleteLastText(transform(text))
    override fun setDictationPreview(newText: String, prevText: String) =
        inner.setDictationPreview(transform(newText), transform(prevText))
    override fun commitDictationFinal(finalText: String, prevText: String): Boolean {
        val out = transform(finalText)
        return inner.commitDictationFinal(out, transform(prevText)).also { if (it) proofread(out) }
    }

    /** Hands committed Chinese dictation to proofreading, when that is on (P2-08). */
    private fun proofread(text: String) {
        io.zizai.ime.ZizaiIme.current?.onDictationCommitted(text)
    }
    override fun clearDictationPreview(prevText: String) = inner.clearDictationPreview(transform(prevText))
    override fun holdsPreview(prevText: String) = inner.holdsPreview(transform(prevText))

    companion object {
        /** The hook: wraps whatever sink upstream picked. */
        fun wrap(context: Context, inner: DictationSink): DictationSink {
            val prefs = ZizaiPrefs.get(context)
            val converter = runCatching { HanAssets.converter(context) }.getOrNull()
            return TranscriptPostProcessingSink(inner) { text ->
                process(text, converter, prefs.outputScript, prefs.punctuationLocale, prefs.cjkLatinSpacing)
            }
        }

        fun process(
            text: String,
            converter: ZhConverter?,
            script: ZhScript,
            locale: PunctuationLocale,
            cjkLatinSpacing: Boolean,
        ): String {
            if (text.isEmpty() || text.codePoints().noneMatch(ZhText::isHan)) return text
            var t = ZhText.removeSpacesBetweenHan(text)
            t = ZhText.normalizePunctuation(t, locale)
            if (converter != null) t = converter.convert(t, script)
            if (cjkLatinSpacing) t = ZhText.cjkLatinSpacing(t)
            return t
        }
    }
}
