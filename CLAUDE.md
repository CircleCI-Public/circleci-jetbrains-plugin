# Claude Development Guide

Quick reference for AI assistants working on this CircleCI IntelliJ plugin.

## Commands

**Always use Taskfile over raw Gradle:**

```bash
task build      # Build plugin
task test       # Run tests + verify
task run        # Launch IDE
task lint       # Static analysis
task format     # Auto-fix style
task release    # Full release
```

View all: `task --list`

## Before Committing

```bash
task format     # Fix style issues
task lint       # Check code quality
task test       # Verify tests pass
```

## Key Info

- **Language**: Kotlin
- **Tests**: JUnit 4 (not 5) - signature: `assertEquals(message, expected, actual)`
- **Style**: ktlint (auto-fix with `task format`)
- **LSP Version**: lsp4ij 0.19.1
- **Excluded Test**: CircleCIStateStoreTest (requires IDE environment)

## File Structure

```
src/main/kotlin/com/circleci/idea/
  ├── api/            # API client
  ├── toolwindow/     # Main UI
  ├── state/          # State management
  ├── lsp/            # Language server
  └── ...

config/               # Static analysis configs
docs/                 # Plugin repository
```

## State Management

Use Kotlin Flows:
```kotlin
private val _state = MutableStateFlow(value)
val state: StateFlow<T> = _state.asStateFlow()
```

## Docs

- `DEVELOPMENT.md` - Developer guide
- `STATIC_ANALYSIS.md` - Code quality tools
- `ARCHITECTURE.md` - System design
- `CHANGELOG.md` - Version history

## Common Issues

**Build fails?** `task clean && task build`
**Style errors?** `task format`
**Tests fail?** Check JUnit 4 assertion signatures

That's it! Keep it simple. 🚀
