# CLAUDE.md

Instructions for Claude Code in this repository. Workflow rules that apply to everyone live in [`CONTRIBUTING.md`](CONTRIBUTING.md); follow them. This file adds only what is specific to working with the AI assistant.

## Project

Axion is an open-source, API-first billing and receivables platform on simulated Brazilian payment rails. It is a demonstration project engineered as a production financial system.

Sources of truth, to read before planning any work:

- `docs/design-docs/2026-11-17-platform-overview.md`: the approved platform design.
- `docs/roadmap.md`: phases and the PR-by-PR plan. Work follows the roadmap order.

When this file and the design doc disagree, the design doc wins; point out the conflict.

## Working rules

- Each commit introduces **one concept**.
- Slice a roadmap item only for one of the reasons in [`CONTRIBUTING.md`](CONTRIBUTING.md#pull-request-size), never to fit a size; a PR that splits or adds slices updates the roadmap itself.
- Prefer explicit, readable code over clever code. Code follows the comment standard in [`engineering-standards.md`](docs/architecture/engineering-standards.md#comments-and-documentation).
- Verify before claiming: run the build, tests or scripts and report the real output.

## Language

- Everything committed to the repository is in **English** (see `CONTRIBUTING.md`).

## Boundaries

- Ask before any outward-facing action (push, issues, pull requests, GitHub settings) unless the author asked for it in the current request.
- Create the issue before the pull request, so `Closes #<issue>` points to a real issue.
- Never merge pull requests; the author merges.
- Never rewrite or force-push signed history on `main`.
- Never commit secrets or real personal data; all data in this project is synthetic.
