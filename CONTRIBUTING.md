# Contributing to Finaxis

This document is the single source of truth for how work is done in this repository.

## Setup

Development happens on Linux (or WSL2 on Windows, with the repository on the Linux filesystem). Verify your toolchain:

```bash
./scripts/check-toolchain.sh
```

Gitleaks and editorconfig-checker are single binaries: download them from their official GitHub releases, verify the published SHA-256 checksum, and place them on your `PATH` (e.g. `~/.local/bin`).

Install the repository tooling and git hooks:

```bash
pnpm install
```

Commits must be signed (SSH or GPG): `main` rejects unsigned commits.

### Git hooks

[Lefthook](https://lefthook.dev) runs these checks locally ([`lefthook.yml`](lefthook.yml)):

| Hook | Check |
|---|---|
| `pre-commit` | Gitleaks scans staged changes for secrets |
| `pre-commit` | editorconfig-checker validates staged files against [`.editorconfig`](.editorconfig) |
| `commit-msg` | commitlint validates the message against [Commits](#commits) |

Do not bypass hooks with `--no-verify`. If a hook reports a false positive, fix its configuration in a pull request instead.

## Continuous integration

GitHub Actions runs these security gates on every pull request and on `main` ([`.github/workflows/`](.github/workflows)):

| Workflow | Check |
|---|---|
| Gitleaks | Scans the full git history for secrets |
| CodeQL | Static analysis (SAST); findings appear under **Security → Code scanning** |

Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml)) opens weekly, grouped update PRs for GitHub Actions and the root pnpm tooling.

Workflow rules:

- Actions are pinned to a full commit SHA, with the version in a comment (`uses: owner/action@<sha> # vX.Y.Z`). Tags can be moved; SHAs cannot.
- Permissions are read-only by default (`permissions: contents: read`); a job asks only for what it needs.
- Downloaded tools are verified against their published SHA-256 checksum.

Security vulnerabilities are reported privately; see [`SECURITY.md`](SECURITY.md).

## Workflow

Every change follows the same path:

1. **Issue:** one issue per pull request, assigned to the milestone of its phase.
2. **Branch:** created from an up-to-date `main`, named `<type>/<short-description>` (e.g. `feat/ledger-domain`).
3. **Commits:** small and atomic, one concept per commit (see [Commits](#commits)).
4. **Pull request:** opened from the template, with `Closes #<issue>` in the description.
5. **Review:** the author reviews the change and answers the review questions in the description. Fixes are added as new commits; history is not rewritten during review.
6. **Merge:** the branch is rebased locally onto `main`, then merged with a **merge commit**. GitHub's "rebase and merge" rewrites commits and drops their signatures, so it is not used.

Changes that introduce a significant feature start with a design doc PR.

## Pull request size

- One PR is one small, complete slice: code, tests and docs together.
- Phases 0 and 1 target about **50–200 changed lines** per PR; the hard limit is about 400.
- **3–8 commits** per PR.
- Generated or verbatim third-party text (e.g. license files, lockfiles) does not count toward the limit, but should be called out in the description.

## Commits

Messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <imperative summary, lower case, no period>

<optional body: what and why, wrapped at 72 characters>
```

| Type | Use for |
|---|---|
| `feat` | A new capability |
| `fix` | A bug fix |
| `docs` | Documentation only |
| `test` | Tests only |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `build` | Build system and dependencies |
| `ci` | CI configuration |
| `chore` | Repository maintenance |

## Definition of Done

A pull request is ready to merge when:

- [ ] Tests cover the change and pass.
- [ ] Documentation affected by the change is updated.
- [ ] The security checklist in the PR template is complete.
- [ ] CI is green (once CI exists).
- [ ] The size limit is respected.

## Language

Code, documentation, ADRs, commits, issues and pull requests are written in **English**. Domain terms that are proper names stay in Portuguese (`Pix`, `Boleto`, `Cnab`).

## AI assistance

This project is built with AI assistance. AI-generated changes go through the same workflow and review as any other change; the author reviews every change before merging. Instructions for the AI assistant are in [`CLAUDE.md`](CLAUDE.md).
