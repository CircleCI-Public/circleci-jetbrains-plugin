package main

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

type version struct{ major, minor, patch int }

func (v version) String() string { return fmt.Sprintf("%d.%d.%d", v.major, v.minor, v.patch) }

func (v version) tag() string { return "v" + v.String() }

type bump int

const (
	bumpPatch bump = iota
	bumpMinor
	bumpMajor
)

func (v version) bump(b bump) version {
	switch b {
	case bumpMajor:
		return version{major: v.major + 1}
	case bumpMinor:
		return version{major: v.major, minor: v.minor + 1}
	default:
		return version{major: v.major, minor: v.minor, patch: v.patch + 1}
	}
}

// bumpFor is the largest bump the PRs' labels ask for. A PR without a release:major or
// release:patch label asks for a minor bump.
func bumpFor(prs []pullRequest) bump {
	b := bumpPatch
	for _, pr := range prs {
		pb := bumpMinor
		for _, l := range pr.Labels {
			switch l.Name {
			case "release:major":
				pb = bumpMajor
			case "release:patch":
				pb = bumpPatch
			}
		}
		b = max(b, pb)
	}
	return b
}

var versionLine = regexp.MustCompile(`(?m)^version = "(\d+)\.(\d+)\.(\d+)"$`)

func gradleVersion(gradle string) (version, error) {
	m := versionLine.FindStringSubmatch(gradle)
	if m == nil {
		return version{}, fmt.Errorf(`no 'version = "x.y.z"' line in build.gradle.kts`)
	}
	var v version
	v.major, _ = strconv.Atoi(m[1])
	v.minor, _ = strconv.Atoi(m[2])
	v.patch, _ = strconv.Atoi(m[3])
	return v, nil
}

func setGradleVersion(gradle string, v version) string {
	return versionLine.ReplaceAllLiteralString(gradle, fmt.Sprintf("version = %q", v.String()))
}

var htmlComment = regexp.MustCompile(`(?s)<!--.*?-->\n*`)

// addRelease adds a section for the release above the newest one. GitHub's notes have level 2
// headings, which are demoted to stay inside the section, where the IDE's changelog plugin
// expects them.
func addRelease(changelog string, v version, date, notes string) string {
	notes = htmlComment.ReplaceAllString(notes, "")
	notes = regexp.MustCompile(`(?m)^## `).ReplaceAllString(notes, "### ")
	section := fmt.Sprintf("## [%s] - %s\n\n%s\n\n", v, date, strings.TrimSpace(notes))

	i := strings.Index(changelog, "\n## ")
	if i < 0 {
		return strings.TrimRight(changelog, "\n") + "\n\n" + section
	}
	return changelog[:i+1] + section + changelog[i+1:]
}

// releaseNotes is the body of the release's section of the changelog.
func releaseNotes(changelog string, v version) (string, error) {
	header := fmt.Sprintf("## [%s]", v)
	start := -1
	for _, prefix := range []string{"\n" + header + " ", "\n" + header + "\n"} {
		if i := strings.Index(changelog, prefix); i >= 0 {
			start = i + 1
			break
		}
	}
	if start < 0 {
		return "", fmt.Errorf("CHANGELOG.md has no %s section", header)
	}
	body := changelog[start:]
	body = body[strings.Index(body, "\n")+1:]
	if end := strings.Index(body, "\n## "); end >= 0 {
		body = body[:end]
	}
	return strings.TrimSpace(body), nil
}
