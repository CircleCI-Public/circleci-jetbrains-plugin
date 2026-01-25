# Development Guide

This guide provides detailed information for developers working on the CircleCI JetBrains plugin.

## Architecture Overview

The plugin follows the standard JetBrains plugin architecture with these key components:

### Core Components

1. **Tool Window** (`toolwindow/`)
   - Main UI for displaying pipelines, workflows, and jobs
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
   - Shows current pipeline status
   - Quick access to CircleCI panel

## Development Workflow

### Setting Up Your Environment

1. Clone the repository
2. Open in IntelliJ IDEA
3. Wait for Gradle sync to complete
4. Run the plugin using the "Run Plugin" run configuration

### Running the Plugin

```bash
# Run in sandboxed IDE
./gradlew runIde

# Run with debugging
./gradlew runIde --debug-jvm
```

### Testing

```bash
# Run all tests
./gradlew test

# Run specific test
./gradlew test --tests "com.circleci.idea.api.*"

# Generate coverage report
./gradlew test jacocoTestReport
```

### Building

```bash
# Build plugin
./gradlew buildPlugin

# Build and verify
./gradlew buildPlugin verifyPlugin
```

## Code Style

- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions small and focused
- Use dependency injection where appropriate

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
data class Pipeline(
    val id: String,
    val number: Int,
    val projectSlug: String,
    val state: String,
    val createdAt: Instant
)
```

## UI Components

### Tree View

The main tree view displays:
- Projects (root nodes)
- Pipelines
- Workflows
- Jobs

Use `AsyncTreeModel` for efficient data loading.

### Icons

Place SVG icons in `src/main/resources/icons/`:
- `circleci.svg` - Main plugin icon
- Status icons for pipelines, workflows, jobs

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
    private val _pipelinesFlow = MutableStateFlow<List<Pipeline>>(emptyList())
    val pipelinesFlow: StateFlow<List<Pipeline>> = _pipelinesFlow.asStateFlow()
}
```

## Testing Strategy

### Unit Tests

Test business logic and API client:

```kotlin
@Test
fun `test pipeline parsing`() {
    val json = """{"id": "123", "number": 1}"""
    val pipeline = parsePipeline(json)
    assertEquals("123", pipeline.id)
}
```

### Integration Tests

Test IDE integration:

```kotlin
@Test
fun `test tool window creation`() {
    val toolWindow = ToolWindowManager.getInstance(project)
        .getToolWindow("CircleCI")
    assertNotNull(toolWindow)
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
