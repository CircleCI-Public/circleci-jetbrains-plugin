package main

import (
	"testing"

	"gotest.tools/v3/assert"
	"gotest.tools/v3/assert/cmp"
)

func pr(number int, labels ...string) pullRequest {
	p := pullRequest{Number: number}
	for _, l := range labels {
		p.Labels = append(p.Labels, struct {
			Name string `json:"name"`
		}{l})
	}
	return p
}

func TestBumpFor(t *testing.T) {
	tests := []struct {
		name string
		prs  []pullRequest
		want bump
	}{
		{"unlabelled PRs are minor", []pullRequest{pr(1), pr(2, "bug")}, bumpMinor},
		{"all patch", []pullRequest{pr(1, "release:patch"), pr(2, "release:patch")}, bumpPatch},
		{"an unlabelled PR outranks patch", []pullRequest{pr(1, "release:patch"), pr(2)}, bumpMinor},
		{"any major", []pullRequest{pr(1, "release:patch"), pr(2, "release:major"), pr(3)}, bumpMajor},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := bumpFor(tt.prs)
			assert.Check(t, cmp.Equal(got, tt.want))
		})
	}
}

func TestBump(t *testing.T) {
	v := version{1, 3, 1}
	for b, want := range map[bump]string{bumpPatch: "1.3.2", bumpMinor: "1.4.0", bumpMajor: "2.0.0"} {
		got := v.bump(b).String()
		assert.Check(t, cmp.Equal(got, want))
	}
}

func TestGradleVersion(t *testing.T) {
	gradle := `group = "com.circleci"
version = "1.3.1"

intellijPlatform {
    version = project.version.toString()
}
`
	v, err := gradleVersion(gradle)
	assert.NilError(t, err)
	assert.Check(t, cmp.Equal(v, version{1, 3, 1}))

	got := setGradleVersion(gradle, version{1, 4, 0})
	want := `group = "com.circleci"
version = "1.4.0"

intellijPlatform {
    version = project.version.toString()
}
`
	assert.Check(t, cmp.Equal(got, want))
}

const changelog = `# Changelog

Intro.

## [1.3.1] - 2026-01-29

### Fixed
- A fix
`

func TestAddRelease(t *testing.T) {
	notes := "<!-- Release notes generated using configuration in .github/release.yml at main -->\n\n" +
		"## What's Changed\n* Add a thing by @someone in https://github.com/o/r/pull/9\n\n\n" +
		"**Full Changelog**: https://github.com/o/r/compare/v1.3.1...v1.4.0"

	got := addRelease(changelog, version{1, 4, 0}, "2026-10-02", notes)
	want := `# Changelog

Intro.

## [1.4.0] - 2026-10-02

### What's Changed
* Add a thing by @someone in https://github.com/o/r/pull/9


**Full Changelog**: https://github.com/o/r/compare/v1.3.1...v1.4.0

## [1.3.1] - 2026-01-29

### Fixed
- A fix
`
	assert.Check(t, cmp.Equal(got, want))

	notesGot, err := releaseNotes(got, version{1, 4, 0})
	assert.NilError(t, err)
	notesWant := "### What's Changed\n* Add a thing by @someone in https://github.com/o/r/pull/9\n\n\n" +
		"**Full Changelog**: https://github.com/o/r/compare/v1.3.1...v1.4.0"
	assert.Check(t, cmp.Equal(notesGot, notesWant))
}

func TestReleaseNotes(t *testing.T) {
	t.Run("of the last section", func(t *testing.T) {
		got, err := releaseNotes(changelog, version{1, 3, 1})
		assert.NilError(t, err)
		assert.Check(t, cmp.Equal(got, "### Fixed\n- A fix"))
	})

	t.Run("without the section", func(t *testing.T) {
		_, err := releaseNotes(changelog, version{1, 3, 2})
		assert.Check(t, cmp.ErrorContains(err, "no ## [1.3.2] section"))
	})
}
