package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

type gitHub struct {
	api   string
	repo  string
	token string
}

func newGitHub(api, repo, token string) *gitHub {
	return &gitHub{api: strings.TrimRight(api, "/"), repo: repo, token: token}
}

type statusError struct {
	status int
	body   string
}

func (e *statusError) Error() string { return fmt.Sprintf("HTTP %d: %s", e.status, e.body) }

func isNotFound(err error) bool {
	var se *statusError
	return errors.As(err, &se) && se.status == http.StatusNotFound
}

// do sends a request to path, a repo path ("/pulls") or a full URL, and decodes the JSON
// response into out, if it isn't nil.
func (g *gitHub) do(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	u := path
	if strings.HasPrefix(path, "/") {
		u = g.api + "/repos/" + g.repo + path
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return g.send(req, out)
}

func (g *gitHub) send(req *http.Request, out any) error {
	req.Header.Set("Authorization", "Bearer "+g.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s %s: %w", req.Method, req.URL.Path, &statusError{resp.StatusCode, string(b)})
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(b, out)
}

func (g *gitHub) owner() string { return strings.Split(g.repo, "/")[0] }

func (g *gitHub) tagExists(ctx context.Context, tag string) (bool, error) {
	err := g.do(ctx, http.MethodGet, "/git/ref/tags/"+tag, nil, nil)
	if isNotFound(err) {
		return false, nil
	}
	return err == nil, err
}

type pullRequest struct {
	Number   int    `json:"number"`
	MergedAt string `json:"merged_at"`
	Base     struct {
		Ref string `json:"ref"`
	} `json:"base"`
	Head struct {
		Ref string `json:"ref"`
	} `json:"head"`
	Labels []struct {
		Name string `json:"name"`
	} `json:"labels"`
}

// mergedPRs are the PRs to base merged between the tag and sha, other than release PRs.
func (g *gitHub) mergedPRs(ctx context.Context, tag, sha, base string) ([]pullRequest, error) {
	var commits []struct {
		SHA string `json:"sha"`
	}
	for page := 1; ; page++ {
		var cmp struct {
			Commits []struct {
				SHA string `json:"sha"`
			} `json:"commits"`
		}
		path := fmt.Sprintf("/compare/%s...%s?per_page=100&page=%d", tag, sha, page)
		if err := g.do(ctx, http.MethodGet, path, nil, &cmp); err != nil {
			return nil, err
		}
		commits = append(commits, cmp.Commits...)
		if len(cmp.Commits) < 100 {
			break
		}
	}

	seen := map[int]bool{}
	var prs []pullRequest
	for _, c := range commits {
		var cprs []pullRequest
		if err := g.do(ctx, http.MethodGet, "/commits/"+c.SHA+"/pulls", nil, &cprs); err != nil {
			return nil, err
		}
		for _, pr := range cprs {
			if seen[pr.Number] || pr.MergedAt == "" || pr.Base.Ref != base || pr.Head.Ref == releaseBranch {
				continue
			}
			seen[pr.Number] = true
			prs = append(prs, pr)
		}
	}
	return prs, nil
}

// generateNotes are GitHub's release notes for the PRs merged since previous.
func (g *gitHub) generateNotes(ctx context.Context, tag, previous, sha string) (string, error) {
	var notes struct {
		Body string `json:"body"`
	}
	err := g.do(ctx, http.MethodPost, "/releases/generate-notes", map[string]string{
		"tag_name":          tag,
		"previous_tag_name": previous,
		"target_commitish":  sha,
	}, &notes)
	return notes.Body, err
}

func (g *gitHub) commitTree(ctx context.Context, sha string) (string, error) {
	var commit struct {
		Tree struct {
			SHA string `json:"sha"`
		} `json:"tree"`
	}
	err := g.do(ctx, http.MethodGet, "/git/commits/"+sha, nil, &commit)
	return commit.Tree.SHA, err
}

func (g *gitHub) createTree(ctx context.Context, baseTree string, files map[string]string) (string, error) {
	type entry struct {
		Path    string `json:"path"`
		Mode    string `json:"mode"`
		Type    string `json:"type"`
		Content string `json:"content"`
	}
	var entries []entry
	for path, content := range files {
		entries = append(entries, entry{Path: path, Mode: "100644", Type: "blob", Content: content})
	}
	var tree struct {
		SHA string `json:"sha"`
	}
	err := g.do(ctx, http.MethodPost, "/git/trees", map[string]any{"base_tree": baseTree, "tree": entries}, &tree)
	return tree.SHA, err
}

func (g *gitHub) createCommit(ctx context.Context, message, tree, parent string) (string, error) {
	var commit struct {
		SHA string `json:"sha"`
	}
	err := g.do(ctx, http.MethodPost, "/git/commits", map[string]any{
		"message": message, "tree": tree, "parents": []string{parent},
	}, &commit)
	return commit.SHA, err
}

// branch returns the commit the branch points to, or "" if it doesn't exist.
func (g *gitHub) branch(ctx context.Context, name string) (string, error) {
	var ref struct {
		Object struct {
			SHA string `json:"sha"`
		} `json:"object"`
	}
	err := g.do(ctx, http.MethodGet, "/git/ref/heads/"+name, nil, &ref)
	if isNotFound(err) {
		return "", nil
	}
	return ref.Object.SHA, err
}

func (g *gitHub) setBranch(ctx context.Context, name, sha string, exists bool) error {
	if exists {
		return g.do(ctx, http.MethodPatch, "/git/refs/heads/"+name, map[string]any{"sha": sha, "force": true}, nil)
	}
	return g.do(ctx, http.MethodPost, "/git/refs", map[string]string{"ref": "refs/heads/" + name, "sha": sha}, nil)
}

func (g *gitHub) openPR(ctx context.Context, head string) (int, error) {
	var prs []pullRequest
	q := url.Values{"state": {"open"}, "head": {g.owner() + ":" + head}}
	if err := g.do(ctx, http.MethodGet, "/pulls?"+q.Encode(), nil, &prs); err != nil {
		return 0, err
	}
	if len(prs) == 0 {
		return 0, nil
	}
	return prs[0].Number, nil
}

type release struct {
	ID        int    `json:"id"`
	UploadURL string `json:"upload_url"`
}

func (g *gitHub) uploadAsset(ctx context.Context, rel release, name string, content []byte) error {
	u := strings.SplitN(rel.UploadURL, "{", 2)[0] + "?" + url.Values{"name": {name}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(content))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/zip")
	return g.send(req, nil)
}
