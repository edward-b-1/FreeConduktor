package com.freeconductor

import java.util.Properties

/** Application metadata, read from the build-generated version resource. */
object AppInfo {
    /** The app version (e.g. "0.1.21"), matching the Gradle build. "dev" if unavailable. */
    val version: String by lazy {
        runCatching {
            AppInfo::class.java.getResourceAsStream("/freeconductor-version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"
    }
}
