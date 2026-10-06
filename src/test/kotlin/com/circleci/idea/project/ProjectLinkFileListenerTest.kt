package com.circleci.idea.project

import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProjectLinkFileListenerTest : BasePlatformTestCase() {
    fun testCopyIsForTheFileItMakes() {
        val source = myFixture.tempDirFixture.createFile("foo.yml")
        val circleci = myFixture.tempDirFixture.findOrCreateDir(".circleci")

        val copied = isLinkFileEvent(VFileCopyEvent(null, source, circleci, "info.yml"))

        assertTrue("a copy to .circleci/info.yml", copied)
    }

    fun testChangeToAnotherFileIsNot() {
        val file = myFixture.tempDirFixture.createFile(".circleci/config.yml")

        val changed = isLinkFileEvent(VFileContentChangeEvent(null, file, 0, 0))

        assertFalse("a change to .circleci/config.yml", changed)
    }
}
