// Command release publishes the plugin when its release PR has just been merged, and otherwise
// opens or updates the release PR. See RELEASE.md.
package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/alecthomas/kong"
)

type config struct {
	API          string `default:"https://api.github.com" help:"GitHub API URL."`
	Repo         string `default:"CircleCI-Public/circleci-jetbrains-plugin" help:"Repository to release."`
	Base         string `default:"main" help:"Branch releases are made from."`
	SHA          string `env:"CIRCLE_SHA1" required:"" help:"Commit being built."`
	Marketplace  string `default:"https://plugins.jetbrains.com" help:"JetBrains Marketplace URL."`
	PluginID     string `default:"34719" help:"The plugin's Marketplace ID."`
	GitHubToken  string `name:"github-token" env:"GITHUB_TOKEN" required:"" help:"GitHub token."`
	PublishToken string `env:"PUBLISH_TOKEN" help:"JetBrains Marketplace token."`
	now          time.Time
}

func main() {
	var cfg config
	kong.Parse(&cfg, kong.Description("Releases the plugin, or updates its release PR."))
	cfg.now = time.Now()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	err := run(ctx, cfg)
	stop()
	if err != nil {
		_, _ = fmt.Fprintln(os.Stderr, "release:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, cfg config) error {
	gradle, err := os.ReadFile("build.gradle.kts")
	if err != nil {
		return err
	}
	changelog, err := os.ReadFile("CHANGELOG.md")
	if err != nil {
		return err
	}
	v, err := gradleVersion(string(gradle))
	if err != nil {
		return err
	}

	gh := newGitHub(cfg.API, cfg.Repo, cfg.GitHubToken)
	tagged, err := gh.tagExists(ctx, v.tag())
	if err != nil {
		return err
	}
	if !tagged {
		return publish(ctx, cfg, gh, v, string(changelog))
	}
	return updateReleasePR(ctx, cfg, gh, v, string(gradle), string(changelog))
}
