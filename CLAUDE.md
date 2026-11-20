# CLAUDE.md

Instructions for Claude Code in this repository. Workflow rules that apply to everyone live in [`CONTRIBUTING.md`](CONTRIBUTING.md); follow them. This file adds only what is specific to working with the AI assistant.

## Project

Finaxis is an open-source, API-first billing and receivables platform on simulated Brazilian payment rails. It is a demonstration project engineered as a production financial system.

Sources of truth, to read before planning any work:

- `docs/design-docs/2026-11-17-platform-overview.md`: the approved platform design.
- `docs/roadmap.md`: phases and the PR-by-PR plan. Work follows the roadmap order.

When this file and the design doc disagree, the design doc wins; point out the conflict.

## Step-by-step mode

The author is new to Spring. Until told otherwise:

- Work in very small increments. Each commit introduces **one concept** (e.g. add a dependency, then a first controller, then its first test).
- Slice a roadmap item only for one of the reasons in [`CONTRIBUTING.md`](CONTRIBUTING.md#pull-request-size), never to fit a size; a PR that splits or adds slices updates the roadmap itself.
- Prefer explicit, readable code over clever code; explain any framework "magic" the change relies on in the conversation, never in code comments. Code follows the comment standard in [`engineering-standards.md`](docs/architecture/engineering-standards.md#comments-and-documentation).
- Verify before claiming: run the build, tests or scripts and report the real output.

## Explanations to the author

After each commit or PR, explain in the conversation:

1. **What** was done.
2. **Each component** created or changed: its responsibility and how it fits into the system.
3. **Why** this approach, including the alternatives rejected.
4. **How it works**, in plain language.
5. **Files**, grouped by type (code, tests, documentation, configuration), one per line as a clickable relative link, marked new or changed, with what changed in it and why. When files move, add a from → to table.
6. **Review questions** for the author to answer before merging.

Teaching explanations stay in the conversation. Repository artifacts stay professional.

## Language

- Conversation with the author: **Brazilian Portuguese (pt-BR)**.
- Everything committed to the repository: **English** (see `CONTRIBUTING.md`).

## Boundaries

- Ask before any outward-facing action (push, issues, pull requests, GitHub settings) unless the author asked for it in the current request.
- Create the issue before the pull request, so `Closes #<issue>` points to a real issue.
- Never merge pull requests; the author merges.
- Never rewrite or force-push signed history on `main`.
- Never commit secrets or real personal data; all data in this project is synthetic.
