package com.circleci.idea.logging

import com.circleci.idea.CircleCIPlugin
import com.intellij.openapi.application.ApplicationInfo

/**
 * Context information included in logs.
 */
data class LogContext(
    val extensionVersion: String,
    val ideVersion: String,
    val platform: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun create(extensionVersion: String = CircleCIPlugin.version): LogContext {
            val appInfo = ApplicationInfo.getInstance()
            val ideVersion = "${appInfo.versionName} ${appInfo.fullVersion}"
            val platform =
                "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                    "(${System.getProperty("os.arch")})"

            return LogContext(
                extensionVersion = extensionVersion,
                ideVersion = ideVersion,
                platform = platform,
            )
        }
    }

    fun toMap(): Map<String, String> {
        return mapOf(
            "extension_version" to extensionVersion,
            "ide_version" to ideVersion,
            "platform" to platform,
            "timestamp" to timestamp.toString(),
        )
    }
}
