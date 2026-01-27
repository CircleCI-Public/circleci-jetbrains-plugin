package com.circleci.idea.filetype

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

/**
 * File type for CircleCI configuration files.
 * Note: This is a marker file type. Actual YAML support is provided by the YAML plugin.
 */
class CircleCIConfigFileType : FileType {
    override fun getName(): String = "CircleCI Config"

    override fun getDescription(): String = "CircleCI configuration file"

    override fun getDefaultExtension(): String = "yml"

    override fun getIcon(): Icon? = null // TODO: Add icon

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = false

    companion object {
        val INSTANCE = CircleCIConfigFileType()
    }
}
