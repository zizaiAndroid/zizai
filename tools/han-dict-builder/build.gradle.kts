/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

// 字在 Zizai: builds han-core.bin from pinned, license-checked sources (docs/IMPLEMENTATION_PLAN.md P1-04).
// Runs on the developer's machine or in CI, never on the phone.

import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

application {
    mainClass = "io.zizai.tools.handict.MainKt"
}

// Default paths in Main.kt are relative to the repository root.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc loads its native library
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

dependencies {
    implementation(projects.lib.hanEngine)
    implementation(projects.lib.zhText)
    // Reads the upstream Pinyin pack (a SQLite table) for han-ext-modern.bin; build-time only, Apache-2.0.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
