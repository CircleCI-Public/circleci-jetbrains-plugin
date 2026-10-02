# Release Process

Releases are made with [release-please](https://github.com/googleapis/release-please). The
plugin is published to the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34719-circleci) and to
[GitHub releases](https://github.com/CircleCI-Public/circleci-jetbrains-plugin/releases).

## How it works

On every push to main, after the tests pass, the `release` job runs `task ci:release`:

1. `release-please github-release` makes the GitHub release and its tag, if the commit is a
   merged release PR
2. If it did, the signed plugin that `build-and-test` built is uploaded to the JetBrains
   Marketplace and to the GitHub release
3. `release-please release-pr` opens or updates the release PR, which bumps `version` in
   `build.gradle.kts` and adds the release's notes to `CHANGELOG.md`

To release, merge the release PR. The Marketplace's "What's New" is the version's section of
`CHANGELOG.md`. JetBrains reviews each update before it's public.

## Commit messages

release-please only reads commits with a [conventional](https://www.conventionalcommits.org/)
subject. They aren't enforced, but only `feat:` (a minor bump) and `fix:` (a patch bump) commits
make a release, and only conventional commits appear in the release notes. A `!` after the type,
or a `BREAKING CHANGE:` footer, makes a major bump. To release as a particular version, add a
`Release-As: x.y.z` footer.

## Credentials

| Context | Variable | For |
|---|---|---|
| `intellij-plugin-publication` | `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Signing the plugin (base64-encoded PEM) |
| `intellij-plugin-publication` | `PUBLISH_TOKEN` | Uploading to the Marketplace |
| `devex-release` | `GITHUB_TOKEN` | release-please, and uploading to the GitHub release |
