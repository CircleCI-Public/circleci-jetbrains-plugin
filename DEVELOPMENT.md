# Development Guide

This guide provides detailed information for developers working on the CircleCI JetBrains plugin.

## Architecture Overview

The plugin follows the standard JetBrains plugin architecture with these key components:

### Core Components

1. **Tool Window** (`toolwindow/`)
   - Main UI for displaying runs, workflows, and jobs
   - Tree view for hierarchical data display
   - Entry point: `CircleCIToolWindowFactory`

2. **Services** (`services/`)
   - `CircleCIProjectService`: Project-level state management
   - Manages API client lifecycle
   - Handles data refreshing and caching

3. **API Client** (`api/` - to be implemented)
   - HTTP client for CircleCI API v2
   - WebSocket client for real-time updates
   - Request/response models

4. **Settings** (`settings/`)
   - Application-level configuration
   - Secure token storage
   - User preferences

5. **Actions** (`actions/`)
   - IDE actions for menu items and shortcuts
   - Workflow and job management actions
   - Configuration validation actions

6. **Status Bar** (`statusbar/`)
   - Shows current run status
   - Quick access to CircleCI panel

## Development Workflow

### Setting Up Your Environment

1. Clone the repository
2. Open in IntelliJ IDEA
3. Wait for Gradle sync to complete
4. Install Task (taskfile.dev) if not already installed: `brew install go-task/tap/go-task`

### Using Taskfile

This project uses [Taskfile](https://taskfile.dev/) for common development tasks. View all available tasks:

```bash
task --list
```

**Available Commands:**

```bash
# Development
task build          # Build the plugin
task test           # Run tests and verify plugin
task run            # Launch IDE with plugin for testing
task clean          # Clean build artifacts

# Code Quality
task lint           # Run all static analysis (detekt, ktlint, security)
task format         # Auto-format code with ktlint
```

### Running the Plugin

```bash
# Run in sandboxed IDE
task run

# Or use Gradle directly
./gradlew runIde

# Run with debugging
./gradlew runIde --debug-jvm
```

### Testing

```bash
# Run all tests (recommended)
task test

# Or use Gradle directly
./gradlew test

# Run specific test
./gradlew test --tests "com.circleci.idea.api.*"

# Generate coverage report
./gradlew test jacocoTestReport
```

### Building

```bash
# Build plugin (recommended)
task build

# Or use Gradle directly
./gradlew buildPlugin

# Build and verify
./gradlew buildPlugin verifyPlugin
```

### Static Analysis

```bash
# Run all static analysis tools
task lint

# Auto-format code
task format

# See STATIC_ANALYSIS.md for detailed documentation
```

## Code Style

This project uses automated code quality tools:

- **ktlint**: Enforces Kotlin coding conventions
- **detekt**: Detects code smells and potential bugs
- **OWASP Dependency-Check**: Scans for security vulnerabilities

### Running Code Quality Checks

```bash
# Run all static analysis
task lint

# Auto-format code to fix style issues
task format
```

### Coding Conventions

- Follow Kotlin coding conventions (enforced by ktlint)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions small and focused (detekt checks complexity)
- Use dependency injection where appropriate
- Maximum line length: 120 characters
- Use trailing commas in multi-line structures

See [STATIC_ANALYSIS.md](STATIC_ANALYSIS.md) for detailed information about code quality tools.

## Plugin Structure

### Extension Points

The plugin uses these IntelliJ Platform extension points:

- `toolWindow`: Main CircleCI panel
- `applicationConfigurable`: Settings page
- `applicationService`: Application-level services
- `projectService`: Project-level services
- `statusBarWidgetFactory`: Status bar integration
- `fileType`: CircleCI config file type

### Service Architecture

Services follow the dependency injection pattern:

```kotlin
// Application-level service
ApplicationManager.getApplication().getService(CircleCISettings::class.java)

// Project-level service
project.service<CircleCIProjectService>()
```

## API Integration

### CircleCI API Client

The API client will be implemented with these features:

1. **HTTP Client**
   - OkHttp for HTTP requests
   - Retry logic with exponential backoff
   - Request deduplication
   - Response caching

2. **Authentication**
   - Token-based authentication
   - Secure storage using PasswordSafe
   - Token validation

3. **WebSocket Client**
   - Pusher client for real-time updates
   - Auto-reconnection
   - Event-driven architecture

### API Models

Use data classes for API models:

```kotlin
data class Run(
    val id: String,
    val number: Long?,
    val projectSlug: String?,
    val status: RunStatus,
    val createdAt: Instant?
)
```

## UI Components

Prefer Compose with [Jewel](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel),
the IntelliJ Platform's Compose UI, bundled with the IDE. Build new views,
and views you rework, as `@Composable` functions using Jewel's components and
`JewelTheme`, and host them in Swing with `JewelComposePanel`:

```kotlin
val component: JComponent = JewelComposePanel { View() }
```

Stay with Swing where the platform's own component fits better: actions,
toolbars and popup menus (`ActionManager`), dialogs (`DialogWrapper` with the
Kotlin UI DSL), consoles and editors, and the run filters (the Pull Requests
tool's drop-downs).

### Tree View

The run tree, Jewel's `LazyTree` in `RunTreeView`, displays:
- Runs (at the top level) of the selected project, or "My runs" across all projects,
  a page at a time, the next loading as the list scrolls near its end (`state/PagedList.kt`)
- Workflows
- Jobs

### Icons

Place SVG icons in `src/main/resources/icons/`:
- `circleci.svg` - Main plugin icon
- Status icons for runs, workflows, jobs

### Notifications

Use the IntelliJ notification system:

```kotlin
NotificationGroupManager.getInstance()
    .getNotificationGroup("CircleCI Notifications")
    .createNotification("Title", "Content", NotificationType.INFORMATION)
    .notify(project)
```

## State Management

Use a reactive state management approach:

1. Services hold state
2. UI components subscribe to state changes
3. Use Kotlin Flows for reactive updates

```kotlin
class CircleCIProjectService(private val project: Project) {
    private val _runsFlow = MutableStateFlow<List<Run>>(emptyList())
    val runsFlow: StateFlow<List<Run>> = _runsFlow.asStateFlow()
}
```

## Testing Strategy

Prefer integration tests that run the real code against a real local HTTP
server to unit tests with mocks. Don't use Mockito or similar: the test JVM
can't mock the plugin's final classes, and a mock only checks what you told
it to expect.

### Against a local HTTP server

Start the JDK's `HttpServer` on `127.0.0.1:0`, point the real
`CircleCIApiClient(baseUrl = ...)` at it, and check both what it sent and
what it made of the response. `CircleCIApiServiceTest` shows how.

For a service with several endpoints, a fake of it on a local port (as
`FakeCircleCI` is for OAuth) keeps the tests readable.

### Logic without I/O

Mappers, paging (`state/PagedList.kt`), tree building and the like can be tested
directly, passing real functions for what they call:

```kotlin
@Test
fun testRunParsing() {
    val json = """{"id": "123", "attributes": {"number": 1}}"""
    val run = RunMapper.toRun(gson.fromJson(json, RunWire::class.java))
    assertEquals("the id", "123", run?.id)
}
```

## Performance Considerations

1. **Lazy Loading**: Load data only when needed
2. **Caching**: Cache API responses
3. **Background Threads**: Use coroutines for async operations
4. **Virtual Scrolling**: Use for large lists
5. **Debouncing**: Debounce rapid UI updates

## Security

1. **Token Storage**: Use `PasswordSafe` for token storage
2. **Logging**: Never log tokens or sensitive data
3. **HTTPS Only**: All API requests use HTTPS
4. **Input Validation**: Validate all user inputs

## Debugging Tips

1. **Plugin Logs**: Check `idea.log` in sandbox directory
2. **Breakpoints**: Use IntelliJ debugger with runIde
3. **Logging**: Add debug logging with proper log levels
4. **Tool Window Inspector**: Use "Internal Actions" → "UI" → "UI Inspector"

## Common Issues

### Gradle Sync Issues

If Gradle sync fails:
1. Invalidate caches (File → Invalidate Caches)
2. Delete `.gradle` and `.idea` directories
3. Re-import project

### Plugin Not Loading

1. Check plugin.xml syntax
2. Verify all required classes exist
3. Check IDE logs for errors

### Action Not Appearing

1. Verify action is registered in plugin.xml
2. Check action group placement
3. Verify action class exists and is accessible

## Resources

- [IntelliJ Platform SDK Docs](https://plugins.jetbrains.com/docs/intellij/)
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- [CircleCI API Documentation](https://circleci.com/docs/api/v2/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
