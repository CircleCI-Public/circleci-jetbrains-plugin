package com.circleci.idea

import com.circleci.idea.ssh.SshConnector
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The plugin's descriptors load in the IDE. The plugin verifier doesn't catch
 * everything start-up rejects (a dependency on an internal module, say), so
 * a descriptor mistake shows up here instead of as a plugin that silently
 * doesn't load.
 */
class PluginDescriptorTest : BasePlatformTestCase() {
    fun testPluginLoads() {
        assertTrue("plugin loaded", PluginManagerCore.isLoaded(PluginId.getId("com.circleci.idea")))
    }

    fun testVersionIsTheDescriptors() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.circleci.idea"))
        assertEquals("plugin version", descriptor?.version, CircleCIPlugin.version)
    }

    fun testSshConnectorRegisteredWithSshAndTerminal() {
        assertTrue("SSH plugin present", PluginManagerCore.isLoaded(PluginId.getId("intellij.ssh.plugin")))
        assertTrue("Terminal present", PluginManagerCore.isLoaded(PluginId.getId("org.jetbrains.plugins.terminal")))
        assertNotNull("ssh-terminal-support.xml registers the connector", SshConnector.getInstance())
    }
}
