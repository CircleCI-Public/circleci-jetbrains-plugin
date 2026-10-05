package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
)

const releaseBranch = "release/next"

func updateReleasePR(ctx context.Context, cfg config, gh *gitHub, current version, gradle, changelog string) error {
	prs, err := gh.mergedPRs(ctx, current.tag(), cfg.SHA, cfg.Base)
	if err != nil {
		return err
	}
	open, err := gh.openPR(ctx, releaseBranch)
	if err != nil {
		return err
	}
	if len(prs) == 0 {
		fmt.Println("Nothing merged since", current.tag())
		if open == 0 {
			return nil
		}
		err := gh.do(ctx, http.MethodPatch, fmt.Sprintf("/pulls/%d", open), map[string]string{"state": "closed"}, nil)
		if err != nil {
			return err
		}
		return gh.do(ctx, http.MethodDelete, "/git/refs/heads/"+releaseBranch, nil, nil)
	}

	next := current.bump(bumpFor(prs))
	fmt.Printf("Releasing %d PRs as %s\n", len(prs), next.tag())
	notes, err := gh.generateNotes(ctx, next.tag(), current.tag(), cfg.SHA)
	if err != nil {
		return err
	}

	if err := pushReleaseBranch(ctx, cfg, gh, next, map[string]string{
		"build.gradle.kts": setGradleVersion(gradle, next),
		"CHANGELOG.md":     addRelease(changelog, next, cfg.now.UTC().Format("2006-01-02"), notes),
	}); err != nil {
		return err
	}

	pr := map[string]string{
		"title": "Release " + next.tag(),
		"body": fmt.Sprintf("Merging this releases %s to the JetBrains Marketplace and GitHub.\n\n"+
			"A merged PR's `release:major` or `release:patch` label changes the version; "+
			"it's otherwise a minor release.\n\n"+
			"---\n\n%s", next.tag(), notes),
	}
	if open != 0 {
		return gh.do(ctx, http.MethodPatch, fmt.Sprintf("/pulls/%d", open), pr, nil)
	}
	pr["head"], pr["base"] = releaseBranch, cfg.Base
	return gh.do(ctx, http.MethodPost, "/pulls", pr, nil)
}

// pushReleaseBranch points the release branch at a commit on sha with the files changed. It's
// left alone if it already has them, so CI doesn't run again for the same change.
func pushReleaseBranch(ctx context.Context, cfg config, gh *gitHub, next version, files map[string]string) error {
	base, err := gh.commitTree(ctx, cfg.SHA)
	if err != nil {
		return err
	}
	tree, err := gh.createTree(ctx, base, files)
	if err != nil {
		return err
	}
	existing, err := gh.branch(ctx, releaseBranch)
	if err != nil {
		return err
	}
	if existing != "" {
		existingTree, err := gh.commitTree(ctx, existing)
		if err != nil {
			return err
		}
		if existingTree == tree {
			fmt.Println(releaseBranch, "is up to date")
			return nil
		}
	}
	commit, err := gh.createCommit(ctx, "Release "+next.tag(), tree, cfg.SHA)
	if err != nil {
		return err
	}
	return gh.setBranch(ctx, releaseBranch, commit, existing != "")
}

// publish uploads the signed plugin to the Marketplace, then makes the GitHub release with it.
// The release is a draft until the plugin is attached, as publishing it makes the tag, which
// marks the version released.
func publish(ctx context.Context, cfg config, gh *gitHub, v version, changelog string) error {
	notes, err := releaseNotes(changelog, v)
	if err != nil {
		return err
	}
	zip := filepath.Join("build", "distributions", fmt.Sprintf("circleci-jetbrains-plugin-%s-signed.zip", v))
	plugin, err := os.ReadFile(zip)
	if err != nil {
		return err
	}

	fmt.Println("Uploading", zip, "to the JetBrains Marketplace")
	if err := uploadToMarketplace(ctx, cfg, filepath.Base(zip), plugin); err != nil {
		return err
	}

	fmt.Println("Releasing", v.tag(), "on GitHub")
	var rel release
	if err := gh.do(ctx, http.MethodPost, "/releases", map[string]any{
		"tag_name": v.tag(), "target_commitish": cfg.SHA, "name": v.tag(), "body": notes, "draft": true,
	}, &rel); err != nil {
		return err
	}
	if err := gh.uploadAsset(ctx, rel, filepath.Base(zip), plugin); err != nil {
		return err
	}
	return gh.do(ctx, http.MethodPatch, fmt.Sprintf("/releases/%d", rel.ID), map[string]bool{"draft": false}, nil)
}

func uploadToMarketplace(ctx context.Context, cfg config, name string, plugin []byte) error {
	if cfg.PublishToken == "" {
		return fmt.Errorf("PUBLISH_TOKEN must be set")
	}
	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	if err := form.WriteField("pluginId", cfg.PluginID); err != nil {
		return err
	}
	file, err := form.CreateFormFile("file", name)
	if err != nil {
		return err
	}
	if _, err := file.Write(plugin); err != nil {
		return err
	}
	if err := form.Close(); err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, cfg.Marketplace+"/plugin/uploadPlugin", &body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+cfg.PublishToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("uploading to the Marketplace: HTTP %d: %s", resp.StatusCode, b)
	}
	return nil
}
