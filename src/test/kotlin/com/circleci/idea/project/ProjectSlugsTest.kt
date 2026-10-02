package com.circleci.idea.project

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectSlugsTest {
    private val org = "ec6887ec-7d44-4b31-b468-7e552408ee32"
    private val project = "7097f60c-74d1-4936-8d1a-268d4042a493"

    @Test
    fun testStandalone() {
        assertNull("UUIDs", ProjectSlugs.problem("circleci/$org/$project"))
        assertNotNull("names aren't IDs", ProjectSlugs.problem("circleci/myorg/myproject"))
        assertNotNull("one ID", ProjectSlugs.problem("circleci/$org"))
    }

    @Test
    fun testClassic() {
        assertNull("GitHub", ProjectSlugs.problem("gh/myorg/myrepo"))
        assertNull("Bitbucket", ProjectSlugs.problem("bb/myorg/my.repo-name"))
        assertNotNull("no repo", ProjectSlugs.problem("gh/myorg"))
        assertNotNull("too deep", ProjectSlugs.problem("gh/myorg/myrepo/more"))
    }

    @Test
    fun testNeither() {
        assertNotNull("empty", ProjectSlugs.problem(""))
        assertNotNull("other VCS", ProjectSlugs.problem("gl/myorg/myrepo"))
        assertNotNull("no VCS", ProjectSlugs.problem("myorg/myrepo"))
    }
}
