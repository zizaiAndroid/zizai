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
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import io.zizai.han.candidate.LearnedDictionary
import io.zizai.han.reading.Mandarin
import io.zizai.han.reading.Pinyin
import io.zizai.ime.HanUserDictionary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.florisboard.lib.compose.stringRes

/**
 * 我的词 / My words (docs/IMPLEMENTATION_PLAN.md P2-02): what the keyboard learned and what was blocked,
 * with delete, unblock, and JSON export and import. Works on the same dictionary the keyboard uses, so
 * changes apply at once.
 */
@Composable
fun UserWordsScreen() = FlorisScreen {
    title = stringRes(R.string.zizai__words__title)

    content {
        val context = LocalContext.current
        val dict = remember { HanUserDictionary.get(context) }
        var version by remember { mutableIntStateOf(0) }

        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            report(context, runCatching { write(context, uri, UserWordsJson.encode(dict)) }, R.string.zizai__words__exported)
        }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            report(context, runCatching { UserWordsJson.decodeInto(dict, read(context, uri)) }, R.string.zizai__words__imported)
            version++
        }

        Preference(
            title = stringRes(R.string.zizai__words__export),
            onClick = { export.launch("zizai-words.json") },
        )
        Preference(
            title = stringRes(R.string.zizai__words__import),
            summary = stringRes(R.string.zizai__words__import_summary),
            onClick = { import.launch(arrayOf("application/json", "text/plain")) },
        )

        val entries = remember(version) { dict.entries() }
        val blocked = remember(version) { dict.blockedTexts() }
        if (entries.isEmpty() && blocked.isEmpty()) {
            Text(
                stringRes(R.string.zizai__words__empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (e in entries) {
            WordRow(
                text = e.text,
                detail = e.syllables.joinToString(" ") { Pinyin.display(Mandarin.syllables.spellingOf(it)) } + " · ${e.count}",
            ) {
                dict.remove(e.text, e.syllables)
                version++
            }
        }
        if (blocked.isNotEmpty()) {
            Text(
                stringRes(R.string.zizai__words__blocked),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            for (b in blocked) {
                WordRow(text = b, detail = stringRes(R.string.zizai__words__unblock)) {
                    dict.unblock(b)
                    version++
                }
            }
        }
    }
}

@Composable
private fun WordRow(text: String, detail: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = null) }
    }
}

private fun write(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
}

private fun read(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

private fun report(context: Context, result: Result<*>, success: Int) {
    val message = result.fold({ context.getString(success) }, { "Error: ${it.localizedMessage}" })
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** The export format: versioned, with Pinyin spellings rather than internal ids. */
object UserWordsJson {
    @Serializable
    data class File(val format: String = FORMAT, val version: Int = 1, val entries: List<Word>, val blocked: List<String>)

    @Serializable
    data class Word(val text: String, val pinyin: String, val count: Int, val lastUsed: Long, val assembled: Boolean)

    private const val FORMAT = "zizai-han-user"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(dict: LearnedDictionary): String = json.encodeToString(
        File.serializer(),
        File(
            entries = dict.entries().map { e ->
                Word(e.text, e.syllables.joinToString(" ") { Mandarin.syllables.spellingOf(it) }, e.count, e.lastUsed, e.assembled)
            },
            blocked = dict.blockedTexts(),
        ),
    )

    /** Merges [text] into [dict]; entries whose Pinyin the engine doesn't know are skipped. */
    fun decodeInto(dict: LearnedDictionary, text: String) {
        val file = json.decodeFromString(File.serializer(), text)
        require(file.format == FORMAT) { "not a 字在 word list" }
        for (w in file.entries) {
            val ids = w.pinyin.split(' ').filter { it.isNotEmpty() }.map { Mandarin.syllables.idOf(Pinyin.normalizeSpelling(it)) }
            if (ids.isEmpty() || ids.any { it < 0 }) continue
            dict.merge(w.text, ids.toIntArray(), w.count, w.lastUsed, w.assembled)
        }
        file.blocked.forEach(dict::block)
    }
}
