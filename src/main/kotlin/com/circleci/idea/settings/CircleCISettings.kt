package com.circleci.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for CircleCI.
 */
@State(
    name = "CircleCISettings",
    storages = [Storage("circleci.xml")]
)
class CircleCISettings : PersistentStateComponent<CircleCISettings> {
    var hostUrl: String = "https://circleci.com"
    var notificationsEnabled: Boolean = true
    var myPipelinesOnly: Boolean = false
    var branchFilter: String = "current"
    var logLevel: String = "info"
    var sshKeyPath: String = "" // Path to SSH private key (auto-detected if empty)

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
