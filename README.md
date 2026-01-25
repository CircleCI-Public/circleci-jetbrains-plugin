# CircleCI Plugin for JetBrains IDEs

A JetBrains IDE plugin for monitoring, managing, and debugging CircleCI pipelines directly from your IDE.

## Features

- **Pipeline Monitoring**: View pipelines, workflows, and jobs in a hierarchical tree view
- **Real-time Updates**: Get instant notifications when pipeline status changes
- **Workflow Actions**: Rerun workflows from start or from failed jobs, cancel running workflows, approve on-hold jobs
- **SSH Debugging**: Rerun failed jobs with SSH enabled and connect directly from the IDE
- **Config Validation**: Validate CircleCI YAML configuration files before committing
- **Test Run**: Test configuration changes locally without committing to version control

## Requirements

- IntelliJ IDEA 2023.2+ or compatible JetBrains IDE
- CircleCI account with API token
- Java 17+

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
- Gradle 8.5+ (included via wrapper)
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
- **Branch Filter**: Filter pipelines by branch (current, all, default)
- **My Pipelines Only**: Show only your pipelines
- **Log Level**: Logging verbosity (debug, info, warn, error)

## Usage

### Viewing Pipelines

1. Open the CircleCI tool window (View → Tool Windows → CircleCI)
2. The tree view shows your projects, pipelines, workflows, and jobs
3. Click to expand/collapse items
4. Right-click for context menu actions

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
