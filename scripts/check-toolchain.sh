#!/usr/bin/env bash
# Verifies that the local development toolchain is installed and usable.
# Usage: ./scripts/check-toolchain.sh
# Exits with a non-zero status if any required check fails.
set -euo pipefail

failures=0

ok()   { printf '  \033[32m✔\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✘\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# Prints the first version-like number (e.g. 25.0.5) found in the input.
extract_version() { grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1; }

# Succeeds if version $1 is greater than or equal to version $2.
version_gte() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" = "$2" ]; }

# check_tool <name> <minimum version or "-"> <command that prints the version...>
check_tool() {
  local name="$1" minimum="$2"
  shift 2
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "$name: not found"
    return
  fi
  local version
  version="$("$@" 2>&1 | head -n 1 | extract_version || true)"
  if [ -z "$version" ]; then
    fail "$name: could not read version"
  elif [ "$minimum" != "-" ] && ! version_gte "$version" "$minimum"; then
    fail "$name $version (requires >= $minimum)"
  else
    ok "$name $version"
  fi
}

echo "Tools"
check_tool "Git"            2.34 git --version
check_tool "Java (JDK)"     25   java --version
check_tool "Docker"         -    docker --version
check_tool "Docker Compose" 2.0  docker compose version
check_tool "Node.js"        24   node --version
check_tool "pnpm"           10   pnpm --version
check_tool "uv"             -    uv --version
check_tool "just"           -    just --version
check_tool "GitHub CLI"     -    gh --version
check_tool "Gitleaks"       8.19 gitleaks version
check_tool "editorconfig-checker" 3.0 editorconfig-checker --version

echo "Environment"
if docker info >/dev/null 2>&1; then
  ok "Docker daemon is running"
else
  fail "Docker daemon is not reachable (is Docker Desktop running with WSL integration?)"
fi

if gh auth status >/dev/null 2>&1; then
  ok "GitHub CLI is authenticated"
else
  fail "GitHub CLI is not authenticated (run: gh auth login)"
fi

if [ "$(git config --get commit.gpgsign || true)" = "true" ] \
  && [ -n "$(git config --get user.signingkey || true)" ]; then
  ok "Git commit signing is enabled"
else
  fail "Git commit signing is not configured (commit.gpgsign and user.signingkey)"
fi

if grep -qi microsoft /proc/version 2>/dev/null; then
  case "$PWD" in
    /mnt/*) warn "Working on the Windows filesystem ($PWD); clone inside WSL for speed" ;;
    *)      ok "Running inside WSL on the Linux filesystem" ;;
  esac
fi

echo
if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All checks passed."
