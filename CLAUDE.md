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

Use Jewel (Compose for the IntelliJ Platform, bundled with the IDE) where
possible: for new views, and for views you rework. Host it in Swing with
`JewelComposePanel`, as the run tree (`RunTreeView`), the signed-out view
(`SignedOutView`) and the job page's tabs do.

Stay with Swing where the platform's own component is the better fit:

- Actions, toolbars and popup menus (`ActionManager`), so they behave like
  the rest of the IDE
- Dialogs (`DialogWrapper`, laid out with the Kotlin UI DSL)
- Consoles and editors, such as a step's output
- The run filters, which reuse the Pull Requests tool's drop-downs

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
