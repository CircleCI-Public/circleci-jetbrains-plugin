# Changelog

All notable changes to the CircleCI JetBrains Plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.14.0] - 2026-10-08

### What's Changed
* Redraw the run tree's rows as a refresh changes their status by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/97


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.13.0...v1.14.0

## [1.13.0] - 2026-10-07

### What's Changed
* Stop the logger keeping the plugin loaded as it unloads by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/95


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.12.0...v1.13.0

## [1.12.0] - 2026-10-06

### What's Changed
* Keep refreshing workflows and jobs until they've ended too by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/91
* Test the run tree's loads and refreshes by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/92
* Keep refreshing the jobs of an open run that has ended by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/93


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.11.0...v1.12.0

## [1.11.0] - 2026-10-06

### What's Changed
* Report the plugin's own version in its log and User-Agent by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/89


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.10.0...v1.11.0

## [1.10.0] - 2026-10-06

### What's Changed
* Poll fast only while a run listed is running by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/59
* Poll only the first page of runs, merging it into the list by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/60
* Keep an ended run's workflows on a refresh by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/62
* Rebuild the run tree only when a refresh changed it by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/63
* Update the plugin's state atomically by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/64
* Fix grammar in settings text by @sarahhodne in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/65
* Store the runs listed in one go, for the status bar by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/66
* Scan for projects one at a time, sharing scans asked for at once by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/67
* Read only the end of a long step log by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/68
* Stop reading a response's body when its request is cancelled by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/69
* Read polled responses straight into their models by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/71
* Make the language server's downloads cancellable by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/72
* Close the SSH tabs into jobs as the plugin unloads by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/73
* Abandon a browser login cancelled while it starts by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/74
* Log out once, off the EDT, and stop sending the old token by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/75
* Find a job's SSH details through the logged-in API client by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/76
* Don't leave the log file open for lines logged after it's closed by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/77
* Turn auto-refresh on or off in every open project at once by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/78
* Restart polling from Settings only when its settings change by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/79
* Recompose only a job page's header as the job is re-read by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/81
* Keep re-reading a job after a read of it fails by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/82
* Filter and sort a job's tests off the EDT by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/83
* Add entity IDs to tool window actions by @liamclarkedev in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/80
* Keep artifact file icons with the job's page, not for the session by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/84
* Let an artifact's file go as its editor tab closes by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/85
* Check a restriction's expression again only when it changes by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/86
* Draw the plugin's icon as the CircleCI logo by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/87

### New Contributors
* @sarahhodne made their first contribution in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/65
* @liamclarkedev made their first contribution in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/80

**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.9.0...v1.10.0

## [1.9.0] - 2026-10-06

### What's Changed
* Approve approval jobs directly, and only open build jobs by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/57


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.8.0...v1.9.0

## [1.8.0] - 2026-10-06

### What's Changed
* Make API requests with the JDK's HTTP client, so the plugin can unload by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/55


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.7.0...v1.8.0

## [1.7.0] - 2026-10-06

### What's Changed
* Reuse the API client while the token and host stay the same by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/30
* Keep the token in memory once it's read by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/31
* Check the stored token and find projects off the EDT by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/33
* Keep an ended workflow's jobs on refreshing the run tree by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/34
* Poll the run list quickly only while a run is in progress by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/35
* Read a step's log in pieces, and decode it off the EDT by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/36
* Remove the unused project service and response cache by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/37
* Cancel API requests with the coroutines that made them by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/38
* Wait as long as Retry-After asks before retrying a rate-limited request by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/39
* Stop polling a job page while it isn't on screen by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/40
* Load the run list once when the selected project changes by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/41
* Recompose only the crosshair and card as the pointer moves over a chart by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/42
* Show an open artifact's editor again rather than reading it again by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/43
* Parse API responses as they're read, rather than whole by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/44
* Format log timestamps with a formatter that's safe across threads by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/45
* Buffer the plugin's log file rather than flush it every line by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/46
* Close the plugin's log file as the IDE exits by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/47
* Log failed API requests as warnings, not IDE errors by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/48
* Validate a new login once, rather than once for each open project by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/49
* Update the Log Out action off the EDT by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/50
* Filter a job's tests once typing pauses, and sort them in one pass by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/51
* Measure the run tree's branch names only when they change by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/52
* Check a changed file's name before its path for a project link by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/53
* Let go of the run tree when its tool window content goes by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/54


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.6.0...v1.7.0

## [1.6.0] - 2026-10-05

### What's Changed
* Name the plugin consistent with the repo name by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/26
* Give the language server the token with its setToken command by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/28
* Give the language server the GitHub account's token by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/29


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.5.0...v1.6.0

## [1.5.0] - 2026-10-05

### What's Changed
* Load more runs as the run list is scrolled, and keep them on a refresh by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/14
* Some maintenance on the AGENT guidance by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/15
* Document where to find the platform's sources and the running plugin's logs by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/19
* Split the settings into project and org sections, paging the contexts by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/17
* Add a page for each context, modelled on the web app's by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/20
* Delete contexts from Org Secrets and from their pages by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/21
* Look projects and their organizations up in one place by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/22
* Show the IDE's progress indicator while changing things in CircleCI by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/23
* Show every status as a dot, ring or slashed ring, as circleci does by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/24
* Dispose job and context pages, and what's under them, when they close by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/25


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.4.0...v1.5.0

## [1.4.0] - 2026-10-02

### What's Changed
* Name the CircleCI-Public teams in CODEOWNERS by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/4
* Show project and org settings in a tree below the runs by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/3
* Move to faster resource-class by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/5
* Link to the plugin on the JetBrains Marketplace from the README by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/6
* Remove circleci-people from codeowners by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/8
* Fix the compiler warnings and most deprecated API uses by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/9
* Release by merging a release PR kept up to date by cmd/ci/release by @pete-woods in https://github.com/CircleCI-Public/circleci-jetbrains-plugin/pull/12


**Full Changelog**: https://github.com/CircleCI-Public/circleci-jetbrains-plugin/compare/v1.3.1...v1.4.0

## [Unreleased]

### Added
- A page for each context, in an editor tab, modelled on the web app's: its environment variables, and its group, project and expression restrictions. Open it with Open Context in the tool window's Org Secrets
- Delete a context from the tool window's Org Secrets (Delete, on its row) or from its page's toolbar (Delete Context...), after confirming. Its page closes, and Org Secrets lists the contexts again
- Expression restrictions are completed and checked as they're typed, with CircleCI's own expression library
- "Log In via CircleCI..." logs in with your CircleCI account in the browser, as the IDE's GitHub plugin does: a dialog waits while you approve the plugin on CircleCI (Cancel to give up; it stops waiting after 5 minutes), then the browser returns to the IDE and shows the same page as the CircleCI CLI's login. It's offered in the CircleCI tool window, Tools | CircleCI | Login to CircleCI (beside "Log In with Token..."), and Settings. The token it gets lasts 90 days, after which you log in again; logging in again from the same IDE replaces it, rather than adding another to your account
- "CircleCI Settings..." in the CircleCI tool window's options (gear) menu
- A "Log Out" button on the CircleCI settings page, beside the authentication status
- Until you log in, the CircleCI tool window shows the project and ways to log in, as the IDE's Pull Requests view does. "Log In with Token..." opens a "Log In to CircleCI" dialog (server, token, and Generate... to create one); "Log In via CircleCI..." is a placeholder for browser login. If your token is rejected, the view says why
- "SSH into Job" on a running job's page opens an SSH session into it in a Terminal tab, one per session, using the IDE's SSH client: your ~/.ssh/config, SSH agent and keys, with the IDE asking for a passphrase or to trust the host when needed. It connects where the job's "Enable SSH" step says to, in either format it prints: the ssh.circleci.com proxy (`ssh <job-id>-<execution>@ssh.circleci.com`), or a direct address and port (as runner and server print). "Copy SSH Command" copies the same. Needs the bundled SSH and Terminal plugins

### Fixed
- A request the V3 API rejects says why (its error's title and detail), rather than "Request failed"
- Logging in from the settings page now reaches the projects you have open, whose tool windows only noticed after a restart; changing the auto-refresh settings restarts polling in every open project rather than none
- Refreshing the runs (the Refresh button, or after rerunning or canceling from the tree) no longer collapses the tree or loses the selection: it updates the tree in place. Rebuilding it for a filter or branch change keeps what was open, selected and focused, and restoring the tree doesn't scroll it
- Workflows' jobs failed to load ("Workflow not found") because the V2 jobs endpoint doesn't serve them
- Loading could fail with "Already Executed" when two identical requests overlapped; concurrent identical GETs now share one response
- Projects weren't detected (so no runs showed) until a manual refresh when the tool window opened before the IDE had found the Git repositories; projects are now re-detected once the repositories are mapped
- The current-branch filter follows branch checkouts (in the IDE or a terminal), and uses each project's own repository rather than the first one in the IDE project

### Removed
- Run status desktop notifications, which never appeared: the websocket they listened to was never connected. The unused followed-projects lookup (API v1.1) is gone too

### Changed
- Settings | CircleCI says whether you're logged in in the browser or with a token, and offers both ways to log in in place of the API Token field
- Logging in, validating config, and rerunning and canceling workflows use the V3 API. Config validation resolves private orbs in the project's organization. Only approving a hold and canceling a job still use V2, which have no V3 equivalent yet
- The CircleCI tool window lists one project's runs at a time (from those found in the workspace, or another by slug), with the runs at the top of the tree rather than under a row for their project
- The run filters are a row of drop-downs like the Pull Requests list's (Branch, Status, Created, and a funnel to reset them). Branch always has a value ("Branch: Current [main]"); Status and Created read just their name until set, and can be cleared. The project, and Refresh and Auto-Refresh, are in the tool window's title bar
- The run tree marks statuses with small coloured dots, as the Pull Requests list does: blue running, green passed, red failed, purple on hold, grey otherwise
- Runs take two lines, as the Pull Requests list's rows do: the status dot and title, with the revision, age and author in grey underneath, then the branch and (for GitHub projects) the avatar of whoever triggered the run in aligned columns on the right. Rows narrow with the tool window, cutting text off with "…" rather than running off the edge
- While the runs load or refresh, a thin progress bar runs along the top of the list, as on the Pull Requests list, in place of a "Loading..." row
- The CircleCI tool window now opens at the top of the left stripe, beside Commit and Pull Requests, rather than on the right. An existing layout keeps its place until Window | Restore Default Layout
- The CircleCI tool window lists runs rather than pipelines, from CircleCI's V3 runs, workflows and jobs APIs, and says "run" rather than "pipeline" throughout. With the job details now in editor tabs, the runs are its only view, so it has no tabs
  - Filters match `circleci run get`: branch (current, default, all) or *My runs* across every project; status; and created newer/older than 1 hour to 1 month
  - Runs are labelled with their commit subject, branch, revision and age; jobs show their duration, and queued jobs no longer show as running
  - Auto-refresh updates the tree in place, keeping expanded runs and workflows open
  - "Open in Browser" links to the run, workflow or job's own page
  - Removed the unused branch filter and "Show only my pipelines" options from Settings; the toolbar filters replace them
- Jobs open as editor tabs instead of the single Job Details tab, so several can be open at once. Below the IDE's toolbar of job actions, the page is drawn with the IDE's bundled Compose and Jewel; step output and test messages stay in the IDE's console
- The CircleCI tool window's run tree and signed-out view are drawn with Compose and Jewel too, as the job page is. The filters above the runs stay the IDE's own Pull Requests drop-downs, and right-clicking a run, workflow or job still offers the IDE's actions
  - Steps and output come from the V3 jobs API, grouped by parallel execution when there's more than one
  - A running step's output streams in as it's written, polled every 2 seconds as `circleci run get` does, with its colors
  - Opens on the first failed step, or the running one; a running job's steps update as it goes
  - Steps and the selected step's output share a Steps tab, beside the Tests and Artifacts tabs. As in the web app, a run step's command shows above its output (selectable, with a Copy button), and once a step ends, "CircleCI received exit code N" below it
  - The Tests tab reads the V3 tests API: filter by outcome (failures by default, when there are any) and by name or classname, sort by any column (click a heading again to reverse, and a third time for the job's order), and see the selected test's message with its colors
  - The Artifacts tab is a file tree from the V3 artifacts API (by execution when parallel), with speed search: open an artifact in the IDE (text, or images and the like, up to 8 MiB), download a file, folder or everything, open it in the browser, or copy its URL, from the toolbar or its context menu
  - Artifacts download with your API token, so private projects' artifacts work
  - A new Resource Usage tab charts CPU and memory over the job's run against its resource class's limits, one line per parallel execution. Hover over a chart for each execution's sample at that point in the run. Below the charts, each execution's min, mean, max and peak share of the limit, and its network traffic. Charts are drawn with KoalaPlot
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
