# E2E UI Testing with Remote Robot

Quick guide for running E2E tests with IntelliJ Remote Robot.

## Quick Start

### Option 1: Automated (Recommended for CI)

```bash
task ui:all
```

Runs everything automatically: starts IDE, runs tests, stops IDE.

### Option 2: Interactive (Better for Development)

**Terminal 1 - Start IDE:**
```bash
task ui:start
```

**Terminal 2 - Run Tests:**
```bash
task ui:test
```

**When Done:**
Close IDE window or Ctrl+C in Terminal 1

## Writing Tests

### Basic Structure

```kotlin
class MyE2ETest {
    companion object {
        private lateinit var robot: RemoteRobot

        @BeforeClass
        @JvmStatic
        fun setup() {
            robot = RemoteRobot("http://127.0.0.1:8082")
        }
    }

    @Test
    fun testSomething() {
        val button = robot.find(
            ComponentFixture::class.java,
            byXpath("//div[@text='Click Me']")
        )
        button.click()
    }
}
```

### Using Page Objects

See `pages/CircleCIToolWindowPage.kt` for a reusable page object example:

```kotlin
@Test
fun testWithPageObject() {
    val toolWindow = CircleCIToolWindowPage(robot)
    toolWindow.open()
    toolWindow.expandPipeline("main")
    assertTrue("Tree should be visible", toolWindow.isVisible())
}
```

## Finding Components

While IDE is running with robot-server, inspect components at:
```
http://localhost:8082/
```

Shows component hierarchy with:
- `accessiblename`
- `visible_text`
- `class`
- Other attributes for XPath queries

## Common XPath Patterns

```kotlin
// By accessible name
byXpath("//div[@accessiblename='Refresh']")

// By text
byXpath("//div[@text='CircleCI']")

// By class
byXpath("//div[@class='Tree']")

// Combined
byXpath("//div[@class='StripeButton' and @accessiblename='CircleCI']")

// Contains
byXpath("//div[contains(@class, 'JobDetails')]")
```

## Tips

- Use `waitFor(Duration.ofSeconds(5)) { ... }` for async operations
- Keep tests focused on user workflows, not implementation details
- Use page objects for maintainability
- Tests run against a real IDE instance - they're slower than unit tests
- Check http://localhost:8082/ to debug component location issues

## More Info

- [Remote Robot GitHub](https://github.com/JetBrains/intellij-ui-test-robot)
- [IntelliJ SDK Docs](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html)
