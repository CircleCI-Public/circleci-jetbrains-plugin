# CircleCI Plugin for JetBrains IDEs

A JetBrains IDE plugin for monitoring, managing, and debugging CircleCI runs directly from your IDE.

## Features

- **Run Monitoring**: View runs, workflows, and jobs in a hierarchical tree view
- **Run Filters**: Narrow runs by branch (current, default, all) or show your own runs across every project, by status, and by age
- **Real-time Updates**: Get instant notifications when run status changes
- **Workflow Actions**: Rerun workflows from start or from failed jobs, cancel running workflows, approve on-hold jobs
- **Job Pages**: Open any number of jobs as editor tabs, with their steps and live, colored step output
- **SSH Debugging**: Rerun jobs with SSH enabled and SSH into them in the IDE's Terminal, as many sessions as you like
- **Config Validation**: Validate CircleCI YAML configuration files before committing
- **Test Run**: Test configuration changes locally without committing to version control

## Requirements

- IntelliJ IDEA 2026.1+ or another commercial JetBrains IDE (2026.1+); the native LSP API isn't available in open-source builds or Android Studio
- CircleCI account with API token
- For development: JDK 21 to run Gradle (the build downloads the JDK 25 toolchain automatically)

## Installation

### From JetBrains Marketplace (Coming Soon)

1. Open Settings/Preferences
2. Navigate to Plugins
3. Search for "CircleCI"
4. Click Install

### From Source

1. Clone this repository
2. Run `./gradlew buildPlugin`
3. Install the plugin from disk using the generated `.zip` file in `build/distributions/`

## Development Setup

### Prerequisites

- JDK 17 or higher
- Gradle 9.8+ (included via wrapper)
- IntelliJ IDEA (recommended for development)

### Building the Plugin

```bash
# Build the plugin
./gradlew buildPlugin

# Run the plugin in a sandboxed IDE
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin structure
./gradlew verifyPlugin
```

### Project Structure

```
circleci-idea-plugin/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/circleci/idea/
│   │   │       ├── actions/          # IDE actions
│   │   │       ├── api/              # CircleCI API client
│   │   │       ├── filetype/         # File type definitions
│   │   │       ├── services/         # Application/project services
│   │   │       ├── settings/         # Settings and configuration
│   │   │       ├── statusbar/        # Status bar widget
│   │   │       └── toolwindow/       # Tool window implementation
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── plugin.xml        # Plugin descriptor
│   │       └── icons/                # Plugin icons
│   └── test/
│       └── kotlin/                   # Unit tests
├── build.gradle.kts                  # Build configuration
├── gradle.properties                 # Gradle properties
└── settings.gradle.kts               # Gradle settings
```

## Configuration

### Authentication

1. Create a CircleCI personal API token at https://app.circleci.com/settings/user/tokens
2. Open CircleCI settings in your IDE (Tools → CircleCI → Settings)
3. Enter your API token

### Settings

Available settings:
- **Host URL**: CircleCI instance URL (default: https://circleci.com)
- **Notifications**: Enable/disable desktop notifications
- **Log Level**: Logging verbosity (debug, info, warn, error)

## Usage

### Viewing Runs

1. Open the CircleCI tool window (View → Tool Windows → CircleCI)
2. It shows your projects, their runs, each run's workflows, and their jobs
3. Click to expand/collapse items
4. Right-click for context menu actions

The toolbar filters the run list, much like `circleci run get`:
- **Branch**: runs on the current branch, the default branch, all branches, or *My runs* — the runs you triggered, across all projects
- **Status**: canceled, failed, failing, not run, queued, running, or success
- **Created**: runs newer or older than 1 hour up to 1 month

### Job Pages

Double-click a job (or right-click → Open Job) to open its page in an editor tab; each job gets its own tab.
The page lists the job's steps, grouped by parallel execution when there is more than one, and shows the
selected step's output, streamed while the step runs. It opens on the first failed step, or the running one.
Alongside the Steps tab, the page has tabs for its **Tests** (filter by outcome or name), its **Artifacts**
(a file tree: open, download or open in the browser) and its **Resource Usage** (CPU and memory charted
against the resource class's limits). These fill in once the job ends.

### Managing Workflows

Right-click on a workflow to:
- Rerun from start
- Rerun from failed (for failed workflows)
- Cancel workflow (for running workflows)
- Open in browser

### Validating Configuration

1. Open `.circleci/config.yml`
2. Right-click in the editor
3. Select "Validate CircleCI Config"

## Contributing

Contributions are welcome! Please follow these guidelines:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Run `./gradlew test` to ensure all tests pass
6. Submit a pull request

## Testing

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport

# Run integration tests
./gradlew integrationTest
```

## Debugging

To debug the plugin:

1. Run `./gradlew runIde` with the `--debug-jvm` flag
2. Attach your debugger to port 5005
3. Set breakpoints in your code

## Local testing

### Build from source
1. Run `task build` note the location of the built zip file
2. Go to intellij -> settings -> plugins -> gear icon -> install from disk

### Download a release
1. Run: ```gh release download \
     --repo circleci-petri/circleci-idea-plugin \
     --pattern "circleci-idea-plugin-*.zip" \
   --output ~/Downloads/circleci-idea-plugin.zip```
2. Go to intellij -> settings -> plugins -> gear icon -> install from disk

## Release Process

1. Update version in `build.gradle.kts`
2. Update CHANGELOG.md
3. Create a git tag: `git tag v1.0.0`
4. Push tag: `git push origin v1.0.0`
5. Build release: `./gradlew buildPlugin`
6. Upload to JetBrains Marketplace

## License

MIT License - see LICENSE file for details

## Support

- **Issues**: https://github.com/circleci/circleci-idea-plugin/issues
- **Documentation**: https://circleci.com/docs
- **Community**: https://discuss.circleci.com

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## Credits

Based on the CircleCI VSCode extension architecture and requirements.
