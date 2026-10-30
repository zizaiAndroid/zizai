/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.tools.handict

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/** One pinned input: what it is, where it comes from, its license and the SHA-256 of its decoded bytes. */
data class Source(val name: String, val license: String, val sha256: String, val decode: String, val url: String)

/**
 * `sources.lock`: one source per line, tab-separated `name license sha256 decode url`. `decode` is `none`
 * or `base64` (Gitiles serves raw files base64-encoded). `#` starts a comment.
 */
object SourceLock {
    fun read(file: File): List<Source> = file.readLines().mapNotNull { line ->
        val l = line.substringBefore('#').trim()
        if (l.isEmpty()) return@mapNotNull null
        val f = l.split(Regex("\\s+"))
        require(f.size == 5) { "${file.name}: expected 5 fields, got ${f.size}: $line" }
        Source(f[0], f[1], f[2].lowercase(), f[3], f[4])
    }
}

/**
 * Fails the build on any license that isn't on the allow-list, and on anything GPL, ever. Share-alike
 * data may only feed the separately downloaded packs, never the bundled core (docs/ARCHITECTURE.md §6.8).
 */
object LicenseGate {
    val ALLOWED = setOf("Apache-2.0", "MIT", "BSD-3-Clause", "Unicode-3.0", "CC-BY-4.0")

    /** Sources that only the downloadable packs read, and the extra licenses they may carry. */
    val PACK_ONLY = setOf("pinyin-flex")
    val PACK_ALLOWED = ALLOWED + "CC-BY-SA-4.0"

    fun check(sources: List<Source>) {
        for (s in sources) {
            check("GPL" !in s.license.uppercase()) { "${s.name}: GPL-family license ${s.license} is never allowed" }
            val allowed = if (s.name in PACK_ONLY) PACK_ALLOWED else ALLOWED
            check(s.license in allowed) { "${s.name}: license ${s.license} is not on the allow-list $allowed" }
        }
    }
}

/** Downloads sources into [dir] once and verifies them on every use. */
class SourceCache(private val dir: File) {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun fetch(source: Source): File {
        dir.mkdirs()
        val file = File(dir, source.name)
        if (file.exists() && sha256(file.readBytes()) == source.sha256) return file
        println("Downloading ${source.name} from ${source.url}")
        val response = http.send(
            HttpRequest.newBuilder(URI(source.url)).timeout(Duration.ofMinutes(5)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(response.statusCode() == 200) { "${source.name}: HTTP ${response.statusCode()}" }
        val bytes = when (source.decode) {
            "none" -> response.body()
            "base64" -> Base64.getMimeDecoder().decode(response.body())
            else -> error("${source.name}: unknown decode '${source.decode}'")
        }
        val actual = sha256(bytes)
        check(actual == source.sha256) { "${source.name}: SHA-256 $actual does not match the lock (${source.sha256})" }
        val tmp = File(dir, "${source.name}.part")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "cannot move $tmp to $file" }
        return file
    }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
