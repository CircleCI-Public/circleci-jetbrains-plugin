package com.circleci.idea

import java.util.Properties

/**
 * The plugin itself.
 */
object CircleCIPlugin {
    /** The plugin's version, which the build writes into plugin.properties. */
    val version: String by lazy {
        val properties = Properties()
        CircleCIPlugin::class.java.getResourceAsStream("plugin.properties")?.use { properties.load(it) }
        properties.getProperty("version") ?: "unknown"
    }
}
