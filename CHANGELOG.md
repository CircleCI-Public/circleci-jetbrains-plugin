# Changelog

All notable changes to the CircleCI JetBrains Plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Workflows' jobs failed to load ("Workflow not found") because the V2 jobs endpoint doesn't serve them
- Loading could fail with "Already Executed" when two identical requests overlapped; concurrent identical GETs now share one response
- Projects weren't detected (so no runs showed) until a manual refresh when the tool window opened before the IDE had found the Git repositories; projects are now re-detected once the repositories are mapped
- The current-branch filter follows branch checkouts (in the IDE or a terminal), and uses each project's own repository rather than the first one in the IDE project

### Changed
- The Pipelines tab is now the Runs tab, built on CircleCI's V3 runs, workflows and jobs APIs, and says "run" rather than "pipeline" throughout
  - Filters match `circleci run get`: branch (current, default, all) or *My runs* across every project; status; and created newer/older than 1 hour to 1 month
  - Runs are labelled with their commit subject, branch, revision and age; jobs show their duration, and queued jobs no longer show as running
  - Auto-refresh updates the tree in place, keeping expanded runs and workflows open
  - "Open in Browser" links to the run, workflow or job's own page
  - Removed the unused branch filter and "Show only my pipelines" options from Settings; the toolbar filters replace them
- Jobs open as editor tabs instead of the single Job Details tab, so several can be open at once
  - Steps and output come from the V3 jobs API, grouped by parallel execution when there's more than one
  - A running step's output streams in as it's written, polled every 2 seconds as `circleci run get` does, with its colors
  - Opens on the first failed step, or the running one; a running job's steps update as it goes
  - "Copy SSH Command" copies `ssh <job-id>-<execution>@ssh.circleci.com` for jobs rerun with SSH
  - Removed the Connect SSH button, which never enabled: the V1.1 job details it read have no SSH host
- Migrated the CircleCI YAML Language Server integration from lsp4ij to IntelliJ's native LSP API
  - The plugin no longer requires the Red Hat LSP4IJ plugin
  - The language server shows in the status bar's Language Services widget
  - Requires IntelliJ IDEA or another commercial JetBrains IDE, 2026.1 or newer; open-source builds and Android Studio are no longer supported
- The language server is installed from the latest GitHub release archive, verified against the release's `checksums.txt` and the binary's reported version
  - Installed per version under the IDE system directory, and updated in the background at most once a day (set `lspAutoUpdate` to `never` to opt out)
  - Running servers restart onto a newly installed version
  - Debug logging from the server is turned off

### Infrastructure
- Target platform raised to IntelliJ IDEA 2026.2; supports 2026.1 (build 261) through 2026.2 (262.*)
  - Uses the 2026.1+ `LspIntegrationProvider` / `LspClientDescriptor` API
  - Build auto-provisions the Java 25 toolchain via the Foojay resolver
- Upgraded Kotlin from 1.9.21 to 2.4.20 (API version pinned to 2.3 for 2026.1 compatibility)
- Upgraded Gradle from 8.13 to 9.8.0 and IntelliJ Platform Gradle Plugin from 2.11.0 to 2.19.0
  - Also bumped Kover (0.9.11), detekt (1.23.8), ktlint Gradle plugin (14.2.0) and OWASP dependency-check (13.0.0)
- Status bar widget no longer calls the internal `StatusBar.removeWidget` API on dispose

## [1.3.1] - 2026-01-29

### Fixed
- Job duration now displays correctly in job details panel
  - Fixed API field mapping to use `start_time` and `stop_time` fields
  - Added fallback duration calculation from timestamps
- Job details panel layout improvements
  - Replaced GridBagLayout with cleaner BoxLayout + FlowLayout
  - Job name displays in larger, bold font
  - Metadata row uses bullet separators for better readability
- Manually added projects now persist after clicking refresh button
  - Projects list properly merges git-detected and manually added projects

### Improved
- Reduced verbose debug logging for better log readability

### Infrastructure
- Migrated to IntelliJ Platform Gradle Plugin 2.0
  - Updated from plugin 1.17.4 to 2.11.0 for official IntelliJ Platform 2024.3+ support
  - Upgraded Gradle from 8.5 to 8.13 (required for plugin 2.0)
  - Modernized build configuration using new explicit dependency model
  - Updated environment variable handling to use Gradle providers API
  - All developer workflows and Taskfile commands remain unchanged

## [1.3.0] - 2026-01-29

### Added
- **E2E Testing Framework**
  - Integrated IntelliJ Remote Robot for UI testing
  - Added test commands: `task ui:start`, `task ui:test`, `task ui:all`
  - Example E2E tests and page object pattern
  - UI testing documentation and quick reference guide
  - Component inspection at http://localhost:8082/ during test runs

### Fixed
- Job details panel actions now work properly with correct job/workflow context
  - 'Rerun with SSH' requires job ID parameter
  - Job data properly passed from tree nodes to preserve context
  - Workflow ID stored directly in JobDetails object

## [1.1.2] - 2026-01-28

### Fixed
- Fixed step output display showing "No output" for all job steps
  - CircleCI step output API returns JSON arrays directly, not wrapped in objects
  - Added `getRaw()` method to API client for array response handling
  - Step output now displays correctly in job details panel

### Improved
- Simplified steps tree UI in job details panel
  - Flattened tree structure - actions now display directly without nested groupings
  - Switched to `ColoredTreeCellRenderer` for consistent styling with pipeline tree
  - Removed background highlighting artifacts on unselected items
  - Duration formatted in grayed small text for better readability

## [1.1.1] - 2026-01-27

### Fixed
- Completed JUnit 4 migration to fix test compilation failures
  - Removed JUnit 5 platform configuration from build.gradle.kts
  - Replaced `@TempDir` annotation with JUnit 4's `@Rule` and `TemporaryFolder`
  - Fixed assertion method signatures to match JUnit 4 parameter order
  - Added opentest4j dependency for IntelliJ Platform test compatibility
  - Temporarily excluded CircleCIStateStoreTest (platform integration test requiring IDE environment setup)
  - All 86 unit tests now compile and pass successfully

## [1.1.0] - 2026-01-27

### Added
- **CircleCI YAML Language Server Integration**
  - Real-time validation for CircleCI configuration files
  - Schema-based error detection and diagnostics
  - Code completion and documentation support
  - Automatic schema.json download and updates

### Changed
- Migrated from IntelliJ native LSP framework to lsp4ij library
  - Enables proper rendering of language server diagnostics in editor
  - Improved LSP communication and error display
  - Better integration with IDE UI components

### Fixed
- Language server diagnostics now properly display in the editor
- CircleCI API token authentication passed via environment variable

## [1.0.1] - 2026-01-26

### Fixed
- Updated IDE compatibility to support IntelliJ IDEA 2024.3+ (build 253+)

## [1.0.0] - 2026-01-26

### Added
- **Authentication & Security**
  - Secure token storage in IntelliJ credential store
  - Token validation on login
  - Auto-login on IDE startup

- **Pipeline Monitoring**
  - Tree view displaying pipelines, workflows, and jobs
  - Real-time updates via CircleCI WebSocket integration
  - Auto-refresh on project switch and file changes
  - Branch filtering and search
  - Color-coded status indicators (success, failed, running, etc.)

- **Job Details**
  - Comprehensive job details panel with metadata
  - Steps tree with expandable actions
  - Step output viewer with syntax highlighting
  - Test results display with pass/fail/skip status
  - Job execution timing and resource information

- **Artifact Management**
  - View and download job artifacts
  - Context menu with download, open in browser, copy URL
  - Progress indicators for downloads
  - Automatic file type detection and handling

- **SSH Debugging**
  - Connect to running CircleCI jobs via SSH
  - Automatic SSH key detection from ~/.ssh
  - Platform-specific terminal integration (macOS, Linux, Windows)
  - Copy SSH command to clipboard
  - SSH validation and error handling

- **Workflow & Job Actions**
  - Rerun workflows (all jobs or from failed)
  - Rerun workflows with SSH enabled
  - Cancel running workflows and jobs
  - Approve workflows requiring manual approval
  - Open workflows/jobs in browser

- **Notifications**
  - Real-time desktop notifications for workflow/job completion
  - Configurable notification preferences (enable/disable, status filter)
  - Notification throttling (5-minute window per workflow)
  - Action buttons: View, Approve, Rerun, Disable

- **Status Bar Integration**
  - At-a-glance pipeline status in IDE status bar
  - Dynamic status updates (running, success, failed)
  - Click to open CircleCI tool window
  - Authentication status indicator

- **Settings & Configuration**
  - Personal access token management
  - CircleCI host URL configuration (cloud/server)
  - Custom SSH key path configuration
  - Notification preferences
  - Auto-refresh settings

- **Developer Experience**
  - Comprehensive logging with filtering
  - Error tracking and diagnostics
  - Task automation via Taskfile
  - IDE log monitoring tasks

### Technical Details
- Built with Kotlin and IntelliJ Platform SDK
- Reactive state management with Kotlin Flows
- Service-level architecture for modularity
- OkHttp for API client with retry logic and rate limiting
- Pusher WebSocket for real-time updates
- API v2 for pipelines/workflows, API v1.1 for job details

### Fixed
- Empty steps display by using API v1.1 for job details
- WebSocket connection handling and reconnection
- Token validation error handling

### Security
- Secure credential storage using IntelliJ credential store
- No plaintext token storage
- Secure WebSocket authentication
