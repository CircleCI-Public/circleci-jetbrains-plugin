# Release Process

Releases are made by merging the release PR. The plugin is published to the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34719-circleci) and to
[GitHub releases](https://github.com/CircleCI-Public/circleci-jetbrains-plugin/releases).

## How it works

On every push to main, after the tests pass, the `release` job runs `cmd/ci/release`
(`task ci:release`):

- **If the version in `build.gradle.kts` isn't tagged yet**, its release PR has just been
  merged. The signed plugin that `build-and-test` built is uploaded to the Marketplace, then the
  GitHub release (and its tag) is made with the plugin and the version's section of
  `CHANGELOG.md`.
- **Otherwise** it opens or updates the "Release vX.Y.Z" PR from the `release/next` branch,
  which bumps the version and adds GitHub's notes on the PRs merged since the last release to
  `CHANGELOG.md`. With none merged, the PR is closed.

The Marketplace's "What's New" is the version's section of `CHANGELOG.md`. JetBrains reviews
each update before it's public.

## The version

Releases continue from the `v1.3.1` tag, the version first uploaded to the Marketplace by hand.

A release is a minor bump (1.3.1 → 1.4.0), unless a PR in it has one of these labels:

- `release:major`: a major bump (2.0.0)
- `release:patch`: a patch bump (1.3.2), if every PR in the release has it

Label the PR before it's merged, or relabel it and rerun the latest main build's `release` job.

Don't edit the release PR's branch: it's rewritten on every push to main.

## Credentials

| Context | Variable | For |
|---|---|---|
| `intellij-plugin-publication` | `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` | Signing the plugin (base64-encoded PEM) |
| `intellij-plugin-publication` | `PUBLISH_TOKEN` | Uploading to the Marketplace |
| `devex-release` | `GITHUB_TOKEN` | The release PR and the GitHub release |

## If a release fails part way

The Marketplace won't take the same version twice, so if the job fails after uploading it,
rerunning it fails too. Finish by hand: delete any draft GitHub release it left, and make the
release `vX.Y.Z` on the merge commit, with the version's `CHANGELOG.md` section as its notes and
`circleci-jetbrains-plugin-X.Y.Z-signed.zip` from the job's `build-and-test` artifacts attached.
