package com.circleci.idea.settings

import com.circleci.idea.logging.CircleCILogger
import com.circleci.idea.logging.LogLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

/**
 * Application-level settings for CircleCI.
 */
@State(
    name = "CircleCISettings",
    storages = [Storage("circleci.xml")],
)
class CircleCISettings : PersistentStateComponent<CircleCISettings> {
    var hostUrl: String = "https://circleci.com"
    var notificationsEnabled: Boolean = true
    var logLevel: String = "info"
        set(value) {
            field = value
            CircleCILogger.level = LogLevel.fromString(value)
        }
    var sshKeyPath: String = "" // Path to SSH private key (auto-detected if empty)

    // Auto-refresh settings
    var autoRefreshEnabled: Boolean = true
    var fastPollIntervalSeconds: Int = 30 // While a run is in progress
    var slowPollIntervalSeconds: Int = 120 // Otherwise

    // How the stored token was got: "oauth" (in the browser), "token" (pasted), or "" when logged out.
    var authMethod: String = ""

    // This install's ID for browser logins, made on the first. CircleCI replaces the token it last
    // issued a device rather than adding another, as for the CLI's and VS Code's device IDs.
    var deviceId: String = ""

    // Language Server settings
    var lspEnabled: Boolean = true
    var lspAutoUpdate: String = "automatic" // "automatic" or "never"

    /** This install's [deviceId], making one if there isn't one yet. */
    fun oauthDeviceId(): String {
        if (deviceId.isBlank()) deviceId = UUID.randomUUID().toString()
        return deviceId
    }

    override fun getState(): CircleCISettings {
        return this
    }

    override fun loadState(state: CircleCISettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): CircleCISettings {
            return ApplicationManager.getApplication().getService(CircleCISettings::class.java)
        }
    }
}
