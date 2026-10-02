#!/usr/bin/env bash
# Opens or updates the release PR, as release-please does, but driven by CHANGELOG.md rather
# than commit messages: the PR dates [Unreleased] as the next version and bumps build.gradle.kts
# to it. Merging it releases that version (`task ci:release:publish`). With nothing unreleased,
# an open release PR is closed.
#
# The next version is a minor bump if [Unreleased] has an Added, Changed, Deprecated or Removed
# section, and a patch bump otherwise. A `Release-As: x.y.z` line in the message of a commit
# since the last release sets it instead (the newest wins).
#
# Needs GITHUB_TOKEN, with rights to push branches and open PRs.
set -euo pipefail

repo=CircleCI-Public/circleci-jetbrains-plugin
branch=release/next

current=$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)
notes=$(./gradlew -q getChangelog --unreleased --no-header --no-links --no-empty-sections)
open_pr=$(gh pr list --repo "$repo" --head "$branch" --state open --json number --jq '.[0].number // empty')

if [[ -z "${notes//[[:space:]]/}" ]]; then
  echo "Nothing unreleased in CHANGELOG.md."
  if [[ -n "$open_pr" ]]; then
    gh pr close "$open_pr" --repo "$repo" --delete-branch --comment "Nothing is unreleased in CHANGELOG.md any more."
  fi
  exit 0
fi

since="v$current"
git fetch --quiet origin "refs/tags/$since:refs/tags/$since" 2>/dev/null || since=""
release_as=$(git log --format=%B ${since:+"$since..HEAD"} |
  sed -n 's/^Release-As: *v\{0,1\}\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\) *$/\1/p' | head -n 1)

IFS=. read -r major minor patch <<<"$current"
if [[ -n "$release_as" ]]; then
  next=$release_as
elif grep -Eq '^### (Added|Changed|Deprecated|Removed)$' <<<"$notes"; then
  next="$major.$((minor + 1)).0"
else
  next="$major.$minor.$((patch + 1))"
fi
echo "Releasing $current as $next."

git switch --quiet -C "$branch"
perl -pi -e "s/^version = \".*\"\$/version = \"$next\"/" build.gradle.kts
# A new, empty [Unreleased] above the release's section.
perl -pi -e "s/^## \[Unreleased\]\$/## [Unreleased]\n\n## [$next] - $(date -u +%F)/" CHANGELOG.md
git -c user.name="CircleCI Publish" -c user.email="publish@circleci.com" \
  commit --quiet --no-verify -m "Release v$next" build.gradle.kts CHANGELOG.md

# Pushed only when it changes, so CI doesn't rerun on an identical branch.
if git fetch --quiet origin "refs/heads/$branch" 2>/dev/null && git diff --quiet FETCH_HEAD HEAD; then
  echo "$branch is up to date."
else
  git push --quiet --force "https://x-access-token:${GITHUB_TOKEN}@github.com/$repo.git" "HEAD:refs/heads/$branch"
fi

title="Release v$next"
body="Merging this publishes v$next to the JetBrains Marketplace and GitHub. It's kept up to date with \`[Unreleased]\` in CHANGELOG.md on main.

To release as another version, put \`Release-As: x.y.z\` on its own line in a commit message on main.

---

$notes"
if [[ -n "$open_pr" ]]; then
  gh pr edit "$open_pr" --repo "$repo" --title "$title" --body "$body"
else
  gh pr create --repo "$repo" --base main --head "$branch" --title "$title" --body "$body"
fi
