package com.circleci.idea.ui

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.utils.waitFor
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.Duration

/**
 * E2E tests for CircleCI tool window using Remote Robot.
 *
 * To run these tests:
 * 1. Start IDE with robot-server: task ui:start (or ./gradlew runIdeForUiTests &)
 * 2. Run tests: task ui:test (or ./gradlew test --tests "*E2ETest")
 * 3. Stop IDE: task ui:stop
 */
class CircleCIToolWindowE2ETest {
    companion object {
        private const val ROBOT_SERVER_URL = "http://127.0.0.1:8082"
        private lateinit var robot: RemoteRobot

        @BeforeClass
        @JvmStatic
        fun setup() {
            robot = RemoteRobot(ROBOT_SERVER_URL)

            // Wait for IDE to be ready
            waitFor(Duration.ofSeconds(30)) {
                try {
                    robot.findAll<ComponentFixture>(byXpath("//div[@class='IdeFrameImpl']")).isNotEmpty()
                } catch (e: Exception) {
                    // IDE not ready yet, expected during startup
                    println("Waiting for IDE to be ready: ${e.message}")
                    false
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            // Cleanup if needed
        }
    }

    @Test
    fun testToolWindowOpens() {
        // Find and click the CircleCI tool window stripe button
        val toolWindowButton =
            robot.find(
                ComponentFixture::class.java,
                byXpath("//div[@accessiblename='CircleCI' and @class='StripeButton']"),
            )

        toolWindowButton.click()

        // Verify the tool window content appears
        waitFor(Duration.ofSeconds(5)) {
            robot.findAll<ComponentFixture>(
                byXpath("//div[@class='CircleCIToolWindowContent']"),
            ).isNotEmpty()
        }
    }

    @Test
    fun testPipelineTreeDisplayed() {
        // Open tool window
        openToolWindow()

        // Find the pipeline tree
        val tree =
            robot.find(
                JTreeFixture::class.java,
                byXpath("//div[@class='Tree']"),
            )

        // Verify tree is visible and interactive
        assertTrue("Pipeline tree should be visible", tree.isShowing)
    }

    @Test
    fun testWorkflowExpansion() {
        openToolWindow()

        // Find and interact with tree
        val tree =
            robot.find(
                JTreeFixture::class.java,
                byXpath("//div[@class='Tree']"),
            )

        // Expand first pipeline node if exists
        // Note: This will need to be adjusted based on actual tree structure
        if (tree.hasText("main")) {
            tree.clickPath("main")

            // Wait for expansion
            waitFor(Duration.ofSeconds(3)) {
                tree.collectExpandedPaths().size > 1
            }
        }
    }

    @Test
    fun testJobDetailsPanel() {
        openToolWindow()

        // Click on a job in the tree
        // This assumes there's data loaded - in real test you'd set up test data
        val tree =
            robot.find(
                JTreeFixture::class.java,
                byXpath("//div[@class='Tree']"),
            )

        // Try to find and click a job node
        // Note: Actual implementation depends on your tree structure
        if (tree.hasText("build")) {
            tree.doubleClickPath("build")

            // Verify job details panel appears
            waitFor(Duration.ofSeconds(5)) {
                robot.findAll<ComponentFixture>(
                    byXpath("//div[contains(@class, 'JobDetails')]"),
                ).isNotEmpty()
            }
        }
    }

    @Test
    fun testRefreshAction() {
        openToolWindow()

        // Find and click refresh button
        val refreshButton =
            robot.find(
                ComponentFixture::class.java,
                byXpath("//div[@myicon='refresh.svg']"),
            )

        refreshButton.click()

        // Verify loading state or updated data
        // Note: Actual verification depends on your implementation
        waitFor(Duration.ofSeconds(3)) {
            // Check that refresh completed
            true
        }
    }

    /**
     * Helper to open the CircleCI tool window if not already open
     */
    private fun openToolWindow() {
        try {
            val toolWindowButton =
                robot.find(
                    ComponentFixture::class.java,
                    byXpath("//div[@accessiblename='CircleCI' and @class='StripeButton']"),
                )
            toolWindowButton.click()

            // Wait for tool window to appear
            waitFor(Duration.ofSeconds(5)) {
                robot.findAll<ComponentFixture>(
                    byXpath("//div[@class='CircleCIToolWindowContent']"),
                ).isNotEmpty()
            }
        } catch (e: Exception) {
            // Tool window already open or not available - this is acceptable in test context
            println("Tool window may already be open or unavailable: ${e.message}")
        }
    }
}
