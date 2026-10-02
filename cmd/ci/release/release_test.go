package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"gotest.tools/v3/assert"
	"gotest.tools/v3/assert/cmp"
)

// fakeGitHub answers the requests release makes from canned responses, keyed by method and
// path, and records the bodies it was sent.
type fakeGitHub struct {
	t         *testing.T
	responses map[string]string
	mu        sync.Mutex
	sent      map[string]string
}

func newFakeGitHub(t *testing.T, responses map[string]string) (*fakeGitHub, *httptest.Server) {
	f := &fakeGitHub{t: t, responses: responses, sent: map[string]string{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	return f, srv
}

func (f *fakeGitHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	key := r.Method + " " + strings.TrimPrefix(r.URL.Path, "/repos/o/r")
	if r.URL.RawQuery != "" {
		key += "?" + r.URL.RawQuery
	}
	body, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	f.sent[key] = string(body)
	f.mu.Unlock()
	resp, ok := f.responses[key]
	if !ok {
		http.NotFound(w, r)
		return
	}
	_, _ = io.WriteString(w, resp)
}

func (f *fakeGitHub) body(key string) map[string]any {
	f.t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	s, ok := f.sent[key]
	if !ok {
		f.t.Fatalf("no request %s; got %v", key, f.sent)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		f.t.Fatalf("%s: %v", key, err)
	}
	return m
}

func testConfig(api string) config {
	return config{
		API: api, Repo: "o/r", Base: "main", SHA: "head", GitHubToken: "gh", PublishToken: "jb", PluginID: "34719",
		now: time.Date(2026, 10, 2, 12, 0, 0, 0, time.UTC),
	}
}

func TestPublish(t *testing.T) {
	gh, srv := newFakeGitHub(t, map[string]string{
		"POST /releases": `{"id": 7, "upload_url": "UPLOAD/repos/o/r/releases/7/assets{?name,label}"}`,
		"POST /releases/7/assets?name=circleci-idea-plugin-1.3.1-signed.zip": `{}`,
		"PATCH /releases/7": `{}`,
	})
	gh.responses["POST /releases"] = strings.Replace(gh.responses["POST /releases"], "UPLOAD", srv.URL, 1)

	var marketplace struct {
		auth, pluginID, file string
	}
	jb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/plugin/uploadPlugin" {
			http.NotFound(w, r)
			return
		}
		marketplace.auth = r.Header.Get("Authorization")
		marketplace.pluginID = r.FormValue("pluginId")
		f, _, err := r.FormFile("file")
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		b, _ := io.ReadAll(f)
		marketplace.file = string(b)
	}))
	t.Cleanup(jb.Close)

	t.Chdir(t.TempDir())
	err := os.MkdirAll(filepath.Join("build", "distributions"), 0o755)
	assert.NilError(t, err)
	zip := filepath.Join("build", "distributions", "circleci-idea-plugin-1.3.1-signed.zip")
	err = os.WriteFile(zip, []byte("plugin"), 0o644)
	assert.NilError(t, err)

	cfg := testConfig(srv.URL)
	cfg.Marketplace = jb.URL
	err = publish(context.Background(), cfg, newGitHub(srv.URL, "o/r", "gh"), version{1, 3, 1}, changelog)
	assert.NilError(t, err)

	assert.Check(t, cmp.Equal(marketplace.auth, "Bearer jb"))
	assert.Check(t, cmp.Equal(marketplace.pluginID, "34719"))
	assert.Check(t, cmp.Equal(marketplace.file, "plugin"))
	rel := gh.body("POST /releases")
	assert.Check(t, cmp.DeepEqual(rel, map[string]any{
		"tag_name": "v1.3.1", "target_commitish": "head", "name": "v1.3.1", "body": "### Fixed\n- A fix", "draft": true,
	}))
	asset := gh.sent["POST /releases/7/assets?name=circleci-idea-plugin-1.3.1-signed.zip"]
	assert.Check(t, cmp.Equal(asset, "plugin"))
	undraft := gh.body("PATCH /releases/7")
	assert.Check(t, cmp.DeepEqual(undraft, map[string]any{"draft": false}))
}

func TestUpdateReleasePR(t *testing.T) {
	t.Run("opens it", func(t *testing.T) {
		gh, srv := newFakeGitHub(t, map[string]string{
			"GET /compare/v1.3.1...head?per_page=100&page=1": `{"commits": [{"sha": "a"}, {"sha": "b"}]}`,
			"GET /commits/a/pulls": `[
				{"number": 9, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "main"}, "head": {"ref": "fix"}}
			]`,
			"GET /commits/b/pulls": `[
				{"number": 9, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "main"}, "head": {"ref": "fix"}},
				{"number": 10, "merged_at": null, "base": {"ref": "main"}, "head": {"ref": "closed"}}
			]`,
			"GET /pulls?head=o%3Arelease%2Fnext&state=open": `[]`,
			"POST /releases/generate-notes": `{
				"body": "## What's Changed\n* Fix it by @me in https://github.com/o/r/pull/9"
			}`,
			"GET /git/commits/head": `{"tree": {"sha": "head-tree"}}`,
			"POST /git/trees":       `{"sha": "new-tree"}`,
			"POST /git/commits":     `{"sha": "release-commit"}`,
			"POST /git/refs":        `{}`,
			"POST /pulls":           `{}`,
		})

		err := updateReleasePR(context.Background(), testConfig(srv.URL), newGitHub(srv.URL, "o/r", "gh"),
			version{1, 3, 1}, "version = \"1.3.1\"\n", changelog)
		assert.NilError(t, err)

		notes := gh.body("POST /releases/generate-notes")
		assert.Check(t, cmp.DeepEqual(notes, map[string]any{
			"tag_name": "v1.4.0", "previous_tag_name": "v1.3.1", "target_commitish": "head",
		}))

		files := map[string]string{}
		tree := gh.body("POST /git/trees")
		for _, e := range tree["tree"].([]any) {
			e := e.(map[string]any)
			files[e["path"].(string)] = e["content"].(string)
		}
		assert.Check(t, cmp.Equal(tree["base_tree"], "head-tree"))
		assert.Check(t, cmp.Equal(files["build.gradle.kts"], "version = \"1.4.0\"\n"))
		assert.Check(t, cmp.Contains(files["CHANGELOG.md"], "## [1.4.0] - 2026-10-02\n\n### What's Changed\n* Fix it"))

		ref := gh.body("POST /git/refs")
		assert.Check(t, cmp.DeepEqual(ref, map[string]any{"ref": "refs/heads/release/next", "sha": "release-commit"}))
		pr := gh.body("POST /pulls")
		assert.Check(t, cmp.Equal(pr["title"], "Release v1.4.0"))
		assert.Check(t, cmp.Equal(pr["head"], "release/next"))
		assert.Check(t, cmp.Equal(pr["base"], "main"))
	})

	t.Run("leaves an up-to-date branch", func(t *testing.T) {
		gh, srv := newFakeGitHub(t, map[string]string{
			"GET /compare/v1.3.1...head?per_page=100&page=1": `{"commits": [{"sha": "a"}]}`,
			"GET /commits/a/pulls": `[
				{"number": 9, "merged_at": "2026-10-01T00:00:00Z", "base": {"ref": "main"}, "head": {"ref": "fix"},
					"labels": [{"name": "release:patch"}]}
			]`,
			"GET /pulls?head=o%3Arelease%2Fnext&state=open": `[{"number": 12}]`,
			"POST /releases/generate-notes":                 `{"body": "notes"}`,
			"GET /git/commits/head":                         `{"tree": {"sha": "head-tree"}}`,
			"POST /git/trees":                               `{"sha": "new-tree"}`,
			"GET /git/ref/heads/release/next":               `{"object": {"sha": "old-release-commit"}}`,
			"GET /git/commits/old-release-commit":           `{"tree": {"sha": "new-tree"}}`,
			"PATCH /pulls/12":                               `{}`,
		})

		err := updateReleasePR(context.Background(), testConfig(srv.URL), newGitHub(srv.URL, "o/r", "gh"),
			version{1, 3, 1}, "version = \"1.3.1\"\n", changelog)
		assert.NilError(t, err)

		_, committed := gh.sent["POST /git/commits"]
		assert.Check(t, !committed, "made a commit for an up-to-date branch")
		pr := gh.body("PATCH /pulls/12")
		assert.Check(t, cmp.Equal(pr["title"], "Release v1.3.2"))
	})

	t.Run("closes it with nothing merged", func(t *testing.T) {
		gh, srv := newFakeGitHub(t, map[string]string{
			"GET /compare/v1.4.0...head?per_page=100&page=1": `{"commits": []}`,
			"GET /pulls?head=o%3Arelease%2Fnext&state=open":  `[{"number": 12}]`,
			"PATCH /pulls/12":                     `{}`,
			"DELETE /git/refs/heads/release/next": ``,
		})

		err := updateReleasePR(context.Background(), testConfig(srv.URL), newGitHub(srv.URL, "o/r", "gh"),
			version{1, 4, 0}, "version = \"1.4.0\"\n", changelog)
		assert.NilError(t, err)

		pr := gh.body("PATCH /pulls/12")
		assert.Check(t, cmp.DeepEqual(pr, map[string]any{"state": "closed"}))
		_, deleted := gh.sent["DELETE /git/refs/heads/release/next"]
		assert.Check(t, deleted, "didn't delete the release branch")
	})
}
