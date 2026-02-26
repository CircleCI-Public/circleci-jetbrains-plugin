#!/usr/bin/env bash
# start-claude.sh
#
# Launch Claude Code in this repository with NVD_API_KEY loaded from
# .claude-env (a gitignored secrets file at the repo root).
#
# Usage:
#   ./scripts/start-claude.sh                    # interactive session
#   ./scripts/start-claude.sh -p "your prompt"   # non-interactive, print and exit
#
# Setup (one time):
#   cp scripts/.claude-env.example .claude-env
#   echo 'NVD_API_KEY=your-key-here' >> .claude-env
#   chmod 600 .claude-env

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_ROOT}/.claude-env"

# ── Load secrets file ────────────────────────────────────────────────────────

if [[ -f "$ENV_FILE" ]]; then
  set -o allexport
  # shellcheck disable=SC1090
  source <(grep -E '^[A-Z_][A-Z0-9_]*=' "$ENV_FILE")
  set +o allexport
else
  echo "⚠️  No .claude-env file at $REPO_ROOT/.claude-env"
  echo "   Copy scripts/.claude-env.example, fill in NVD_API_KEY, and chmod 600 it."
  echo "   Continuing with whatever is already in the environment..."
fi

# ── Validate ─────────────────────────────────────────────────────────────────

if [[ -z "${NVD_API_KEY:-}" ]]; then
  echo "⚠️  NVD_API_KEY is not set — OWASP scans will be rate-limited by NVD."
fi

# ── Launch Claude Code ───────────────────────────────────────────────────────

echo "📁 Repo: $REPO_ROOT"
[[ -n "${NVD_API_KEY:-}" ]] && echo "🔍 NVD_API_KEY: ${NVD_API_KEY:0:8}…"
echo ""

cd "$REPO_ROOT"
exec claude "$@"
