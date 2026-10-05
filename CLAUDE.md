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
task sources    # Unpack the IntelliJ Platform's sources (Jewel too) to read
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

## Finding Things

`.intellijPlatform/` is git-ignored: give its paths to searches that skip
ignored files.

- **The IDE version built against**: `intellijIdea("...")` in `build.gradle.kts`
- **The platform's and Jewel's source**: run `task sources`, then search
  `.intellijPlatform/sources/<IDE version>/` (e.g.
  `org/jetbrains/jewel/ui/component/search/`). The IDE's own view of a
  platform class is a decompiled stub, with no method bodies.
- **A library's source**: Gradle's cache, at
  `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/*/<artifact>-<version>-sources.jar`.
  Read a file with `unzip -p <jar> <path>`, or search with `ugrep -z`.
- **The running plugin's IDE log (`task run`)**:
  `.intellijPlatform/sandbox/*/*/log_runIde/idea.log`. A running IDE's
  `-Didea.log.path` names its log directory, so
  `ps -ax -o args= | grep -o 'idea\.log\.path=/[^ ]*'` finds it. The logs in
  `build/idea-sandbox/` aren't the running IDE's. Each start begins with an
  `AppStarter - JVM options:` line. The plugin logs under the `CircleCI`
  category, and errors the platform caught are `SEVERE` lines followed by
  `Plugin to blame: CircleCI`.
- **The plugin's own log**: `~/.circleci-plugin/logs/circleci.log`, every API
  request and its status. Every IDE with the plugin writes to it, the
  everyday one too, so go by the timestamps.

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
