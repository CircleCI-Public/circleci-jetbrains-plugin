package com.circleci.idea.api

import com.circleci.idea.api.models.ConfigValidationResponse
import com.circleci.idea.api.models.ProjectInfo
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleCIApiServiceTest {
    private val gson = Gson()

    @Test
    fun testProjectInfoDeserialization() {
        val json =
            """
            {
                "slug": "gh/test/repo",
                "name": "repo",
                "organization_name": "test",
                "vcs_info": {
                    "provider": "GitHub",
                    "default_branch": "main"
                }
            }
            """.trimIndent()

        val project = gson.fromJson(json, ProjectInfo::class.java)

        assertEquals("gh/test/repo", project.slug)
        assertEquals("repo", project.name)
        assertEquals("test", project.organizationName)
        assertEquals("GitHub", project.vcsInfo?.provider)
        assertEquals("main", project.vcsInfo?.defaultBranch)
    }

    @Test
    fun testConfigValidationResponse() {
        val json =
            """
            {
                "valid": true,
                "errors": []
            }
            """.trimIndent()

        val response = gson.fromJson(json, ConfigValidationResponse::class.java)

        assertTrue(response.valid)
        assertTrue(response.errors.isEmpty())
    }

    @Test
    fun testConfigValidationResponseWithErrors() {
        val json =
            """
            {
                "valid": false,
                "errors": [
                    {"message": "Invalid workflow name"},
                    {"message": "Unknown job reference"}
                ]
            }
            """.trimIndent()

        val response = gson.fromJson(json, ConfigValidationResponse::class.java)

        assertFalse(response.valid)
        assertEquals(2, response.errors.size)
        assertEquals("Invalid workflow name", response.errors[0].message)
        assertEquals("Unknown job reference", response.errors[1].message)
    }
}
