# Release Process

This document explains how to release a new version of the CircleCI IntelliJ plugin to your organization.

## Prerequisites

### One-time Setup

1. **Install Task** (for local releases):
   ```bash
   sh -c "$(curl --location https://taskfile.dev/install.sh)" -- -d
   ```

2. **Install GitHub CLI** (for local releases):
   ```bash
   # macOS
   brew install gh

   # Linux
   # See https://github.com/cli/cli/blob/trunk/docs/install_linux.md
   ```

3. **Configure GitHub Pages**:
   - Run `task setup-pages` to create the docs folder with index.html
   - Go to your GitHub repository Settings → Pages
   - Set Source to "main branch /docs folder"
   - Save and wait for GitHub Pages to deploy
   - Your repository will be available at: `https://YOUR_ORG.github.io/circleci-idea-plugin/updatePlugins.xml`

4. **Set up CircleCI Context**:
   - In CircleCI, create a context named `github-release`
   - Add environment variable `GITHUB_TOKEN` with a GitHub personal access token
   - Token needs `repo` scope for creating releases and pushing to the repository

5. **Update Taskfile variables**:
   - Edit `Taskfile.yml` and set:
     - `GITHUB_ORG`: Your GitHub organization name
     - `GITHUB_REPO`: Your repository name (default: circleci-idea-plugin)

## Release Workflow

### Automated Release (Recommended)

1. **Update the version** in `build.gradle.kts`:
   ```kotlin
   version = "1.0.1"  // Increment as needed
   ```

2. **Update CHANGELOG.md** with release notes

3. **Commit and push**:
   ```bash
   git add build.gradle.kts CHANGELOG.md
   git commit -m "Prepare release v1.0.1"
   git push origin main
   ```

4. **Create and push a version tag**:
   ```bash
   git tag v1.0.1
   git push origin v1.0.1
   ```

5. **Approve the release in CircleCI**:
   - CircleCI will automatically build and test
   - A manual approval step will pause before releasing
   - Approve to create the GitHub release and update the plugin repository

### Manual Release (Local)

If you need to release locally:

```bash
# Ensure you're authenticated with GitHub CLI
gh auth login

# Run the full release process
task release
```

This will:
- Clean previous builds
- Run tests and verification
- Validate version matches git tag (if tagged)
- Build the plugin ZIP
- Generate updatePlugins.xml
- Create GitHub release with plugin attached
- Commit and push the updatePlugins.xml to GitHub Pages

## Available Tasks

View all available tasks:
```bash
task --list
```

Common tasks:
- `task build` - Build the plugin ZIP
- `task test` - Run tests
- `task verify` - Verify plugin compatibility
- `task generate-update-xml` - Generate updatePlugins.xml
- `task create-release` - Create GitHub release
- `task release` - Full release process

## User Installation

Once released, users in your organization can install the plugin:

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → ⚙️ (gear icon) → Manage Plugin Repositories**
3. Add the repository URL:
   ```
   https://YOUR_ORG.github.io/circleci-idea-plugin/updatePlugins.xml
   ```
4. Search for "CircleCI" in the Marketplace tab
5. Click Install
6. Restart IDE

## Version Updates

When you release a new version:
1. Users will automatically see an update notification in IntelliJ
2. They can update through the plugin manager
3. The updatePlugins.xml is automatically updated on GitHub Pages

## Troubleshooting

### "Release already exists"
If you need to re-release the same version:
```bash
gh release delete v1.0.0 -y
task release
```

### CircleCI build fails on tag
Ensure:
- Version in `build.gradle.kts` matches the git tag (without the 'v' prefix)
- GITHUB_TOKEN is set in the `github-release` context
- Token has `repo` scope

### Plugin repository not updating
Check:
- GitHub Pages is enabled and pointing to `/docs` folder
- The `docs/updatePlugins.xml` file was committed and pushed
- GitHub Pages deployment completed (check Settings → Pages)

### Users can't see updates
- Ensure updatePlugins.xml has the correct GitHub release URL
- Check that the release exists and the ZIP is attached
- Have users refresh their plugin repository list
