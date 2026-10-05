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
```

View all: `task --list`

Releases are made by merging the release PR that CI keeps open (see `RELEASE.md`). The
release program is Go, in `cmd/ci/release` (`task ci:release:test`).

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
- **Platform**: IntelliJ IDEA 2026.1+ (`sinceBuild` 261), built against 2026.2
- **LSP**: IntelliJ native LSP API (`com.intellij.modules.lsp`, `LspIntegrationProvider`); not available in open-source IDE builds
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

## UI

Prefer Compose with Jewel, hosted in Swing with `JewelComposePanel`. Stay
with Swing for actions, toolbars and menus, dialogs, consoles and editors.
See `DEVELOPMENT.md` (UI Components).

## Tests

Prefer integration tests against a real local HTTP server to unit tests
with mocks, and don't use Mockito. See `DEVELOPMENT.md` (Testing Strategy).

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
