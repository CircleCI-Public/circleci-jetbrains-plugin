# Release Process

Releases are made by merging the release PR. As with
[release-please](https://github.com/googleapis/release-please), CI keeps one PR open that
releases everything merged since the last release, but it's driven by `CHANGELOG.md` rather than
commit messages, so they needn't be conventional commits.

The plugin is published to the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34719-circleci), and to
[GitHub releases](https://github.com/CircleCI-Public/circleci-jetbrains-plugin/releases).

## Making changes

Describe each user-facing change in `CHANGELOG.md`'s `## [Unreleased]` section, under the
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/) section it belongs in (`### Added`,
`### Changed`, `### Deprecated`, `### Removed`, `### Fixed`, `### Security`), in the PR that
makes it. Infrastructure changes go under `### Infrastructure`.

Only what's in `[Unreleased]` is released: a PR that doesn't add to it doesn't prompt a release.
The section is also the release's notes, on GitHub and in the Marketplace's "What's New".

## The release PR

On every push to main, the `release-pr` job opens, or updates, the "Release vX.Y.Z" PR from the
`release/next` branch. It:

- Bumps `version` in `build.gradle.kts`
- Dates `[Unreleased]` as the release (`## [X.Y.Z] - YYYY-MM-DD`), leaving an empty
  `[Unreleased]` above it
- Shows the release notes in its description

The version goes up by:

- **Minor** (1.3.1 → 1.4.0) if `[Unreleased]` has an Added, Changed, Deprecated or Removed
  section
- **Patch** (1.3.1 → 1.3.2) otherwise

To release as another version (a major, say), put `Release-As: 2.0.0` on its own line in the
message of a commit merged to main. The newest one since the last release wins.

Don't edit the release PR's branch: CI rewrites it on every push to main. Edit `CHANGELOG.md` on
main instead. When nothing is left in `[Unreleased]`, the PR is closed.

## Publishing

Merge the release PR. On that commit to main, the `release` job:

1. Builds and signs the plugin, and publishes it to the JetBrains Marketplace
2. Creates the GitHub release `vX.Y.Z` (and its tag) with the release notes and the signed zip

It does nothing if `vX.Y.Z` is tagged already, so it only publishes once per version.

JetBrains reviews each Marketplace update before it's public, usually within a couple of
working days.

## Credentials

The CI jobs use two contexts:

| Context | Variable | For |
|---|---|---|
| `intellij-plugin-publication` | `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Signing the plugin (base64-encoded PEM) |
| `intellij-plugin-publication` | `PUBLISH_TOKEN` | Uploading to the Marketplace: a token from [My Tokens](https://plugins.jetbrains.com/author/me/tokens), for an account that's a developer of the plugin |
| `devex-release` | `GITHUB_TOKEN` | Pushing `release/next`, opening the PR and creating the GitHub release |

## Tasks

```bash
task release-notes            # The unreleased changes, as the release PR shows them
task release-notes -- 1.3.1   # A released version's notes
task ci:release:pr            # What the release-pr job runs (needs GITHUB_TOKEN)
task ci:release:publish       # What the release job runs (needs every credential above)
```

## Troubleshooting

### The release job failed after publishing to the Marketplace

If the Marketplace upload succeeded but the GitHub release didn't, rerunning the job fails, as
the Marketplace won't take the same version twice. Create the GitHub release by hand instead:

```bash
task sign
task release-notes -- X.Y.Z > /tmp/notes.md
gh release create vX.Y.Z --target <merge commit> --title vX.Y.Z --notes-file /tmp/notes.md \
  build/distributions/circleci-idea-plugin-X.Y.Z-signed.zip
```

### No release PR

Check that `[Unreleased]` has entries (`task release-notes`), and the `release-pr` job's output
on the latest main build.
