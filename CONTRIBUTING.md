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

### Core service build

[`services/core`](services/core) builds with Gradle through the wrapper; no local Gradle install is needed. The wrapper downloads the pinned Gradle version and verifies its SHA-256 checksum.

```bash
cd services/core
./gradlew build      # compile, test and package build/libs/finaxis-core.jar
./gradlew bootRun    # start the service from the build
./gradlew test       # run the tests only
./gradlew pitest     # mutation testing of the ledger (a few minutes; not part of build)
```

Tests use JUnit Jupiter and AssertJ, from Spring Boot's test starter. Rules that must hold for every input, such as `Money` arithmetic, are also checked with property-based tests in [jqwik](https://jqwik.net): each `@Property` runs against generated inputs and, on failure, reports the smallest input that breaks it and the seed that reproduces the run. Tests that start the application run against a real PostgreSQL in Docker, started by [Testcontainers](https://testcontainers.com) ([`PostgresTestConfiguration`](services/core/src/test/java/dev/finaxis/core/PostgresTestConfiguration.java)), so Docker must be running; `just up` is not needed. Test reports are written to `build/reports/tests/test/index.html`.

The ledger's tests are themselves tested with [PIT](https://pitest.org) mutation testing: `pitest` makes small changes to the ledger's compiled code, such as flipping a comparison or removing a call, and runs the ledger tests against each one. A change the tests do not notice "survives" and points at behavior no test checks. The task fails when fewer than 90% of the changes are caught; the report, listing every survivor, is written to `build/reports/pitest/index.html`.

The service is a Spring Boot application; its entry point is [`Application`](services/core/src/main/java/dev/finaxis/core/Application.java). Spring Boot's own versions come from its bill of materials, imported as a Gradle platform, so libraries it manages are declared in the catalog without a version.

| File | Purpose |
|---|---|
| [`gradle/wrapper/gradle-wrapper.properties`](services/core/gradle/wrapper/gradle-wrapper.properties) | Pinned Gradle version and its checksum |
| [`settings.gradle.kts`](services/core/settings.gradle.kts) | Build name and the only allowed repositories |
| [`gradle/libs.versions.toml`](services/core/gradle/libs.versions.toml) | Version catalog: every version the build uses |
| [`build.gradle.kts`](services/core/build.gradle.kts) | Plugins, the Java toolchain and dependencies |
| [`gradle.properties`](services/core/gradle.properties) | Gradle options (configuration and build caches) |

### Code quality gates

`./gradlew build` fails on any of these; there is no separate step to remember:

| Gate | Tool | Fails the build on |
|---|---|---|
| Formatting | [Spotless](https://github.com/diffplug/spotless) with [Palantir Java Format](https://github.com/palantir/palantir-java-format) | Unformatted code or unused imports |
| Bug patterns | [Error Prone](https://errorprone.info) | Common Java mistakes, checked while compiling main and test code |
| Null safety | [NullAway](https://github.com/uber/NullAway) with [JSpecify](https://jspecify.dev) annotations | A possible null dereference anywhere under `dev.finaxis` |
| Zero warnings | `javac -Xlint:all -Werror` | Any javac or Error Prone warning |

```bash
./gradlew spotlessApply    # format the code before committing
```

Types are non-null by default: every package carries `@NullMarked` in its `package-info.java`, and a reference that may be null is annotated `@Nullable` (`org.jspecify.annotations`). Suppressing a check (`@SuppressWarnings`) needs a comment explaining why.

### Module boundaries

The core is a modular monolith ([ADR-0002](docs/adr/0002-modular-monolith.md)): each direct subpackage of `dev.finaxis.core` is a module, such as `shared` (the shared kernel), `observability`, `web` (HTTP API conventions), `access` (who may call the core) or `ledger` (the double-entry record of money movements). Two tests fail the build when the structure is broken; neither starts the application:

| Test | Tool | Fails on |
|---|---|---|
| [`ModuleStructureTest`](services/core/src/test/java/dev/finaxis/core/ModuleStructureTest.java) | [Spring Modulith](https://spring.io/projects/spring-modulith) | A dependency cycle between modules; a module using a type another module does not expose |
| [`ArchitectureTest`](services/core/src/test/java/dev/finaxis/core/ArchitectureTest.java) | [ArchUnit](https://www.archunit.org) | Field injection; Spring or persistence types in a `domain` package; reading the system clock instead of an injected `Clock` |

A module exposes only the types in its own package; its subpackages are internal. A module with business logic keeps its public interface and the types it takes and returns in its own package, and splits the rest into `domain` (plain Java model and rules), `application` (the interface's implementation, which owns the transactions) and `persistence` (the SQL), as `ledger` does. The `shared` module is open: all of its types are visible to every module. A new module is a new subpackage with a `package-info.java`.

### Core service configuration

Configuration lives in [`src/main/resources`](services/core/src/main/resources). [`application.yml`](services/core/src/main/resources/application.yml) holds safe defaults for every environment; a profile file `application-<profile>.yml` overrides them when that profile is active.

| Profile | Active when | Differences |
|---|---|---|
| default | No profile is set (e.g. `java -jar build/libs/finaxis-core.jar`) | Safe defaults; the database is set with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` |
| `local` | `./gradlew bootRun`, or `SPRING_PROFILES_ACTIVE=local` | Connects to the PostgreSQL started by `just up`, with the settings in `infra/compose/.env`; health shows component details; logs are plain text |

The service listens on port 8080. Only the Actuator health endpoint is exposed:

```bash
curl localhost:8080/actuator/health             # {"status":"UP",...}
curl localhost:8080/actuator/health/liveness    # the process is alive
curl localhost:8080/actuator/health/readiness   # the service accepts traffic
```

Exposing another Actuator endpoint is a security decision: it needs a reason in the pull request.

Versions are declared only in the version catalog. To upgrade Gradle, run `./gradlew wrapper --gradle-version <version> --gradle-distribution-sha256-sum <checksum>` with the checksum published at [gradle.org/release-checksums](https://gradle.org/release-checksums/), then run the same command a second time so the wrapper JAR and scripts are updated too.

### Database migrations

The schema is managed by [Flyway](https://documentation.red-gate.com/flyway). Migrations are SQL files in [`src/main/resources/db/migration`](services/core/src/main/resources/db/migration), applied in version order when the service starts; Flyway records each one in the `flyway_schema_history` table.

- Name files `V<version>__<description>.sql` (two underscores), e.g. `V2__create_ledger_schema.sql`. A name Flyway cannot parse fails the build instead of being skipped.
- Migrations are forward-only: an applied migration is never edited or deleted. A change is a new migration.
- Breaking changes follow expand/contract: add the new structure, migrate the code, then remove the old structure in a later migration.
- Each module creates its own schema and database role in its own migrations; the `public` schema is closed to every role but the owner.

### Logging

The service writes console logs as JSON, one object per line, in Spring Boot's [Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html) format. The `local` profile writes plain text instead.

Every HTTP request has a correlation ID, set by [`CorrelationIdFilter`](services/core/src/main/java/dev/finaxis/core/observability/CorrelationIdFilter.java):

- A caller may send `X-Correlation-Id`; it is reused if it is 1–64 characters of letters, digits, `.`, `_` or `-`. Otherwise the service generates a UUID.
- The ID is returned in the `X-Correlation-Id` response header and added as `correlation_id` to every log line written while the request is handled.

```bash
curl -i -H 'X-Correlation-Id: my-request-1' localhost:8080/actuator/health
```

Log through SLF4J (`LoggerFactory.getLogger(...)`), never `System.out`. Never log secrets, credentials or personal data (CPF, CNPJ, names, Pix keys), even though all data in this project is synthetic.

### API errors

Every error response is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details document (`application/problem+json`), built in one place: [`ApiExceptionHandler`](services/core/src/main/java/dev/finaxis/core/web/ApiExceptionHandler.java) in the `web` module.

```bash
curl -i localhost:8080/v1/charges
# HTTP/1.1 401
# Content-Type: application/problem+json
# {"detail":"Authentication is required.","instance":"/v1/charges","status":401,"title":"Unauthorized","code":"unauthorized"}
```

- `code` is stable and snake_case; clients branch on it, never on `title` or `detail`, which are for humans and may change. A handler may set a specific code (such as `idempotency_key_reused`); otherwise it is the HTTP status name (`not_found`, `method_not_allowed`).
- `type` is omitted, which RFC 9457 reads as `about:blank`.
- An exception no handler knows is a bug or an infrastructure failure: it becomes a `500` with the detail `An unexpected error occurred.` and code `internal_server_error`. The exception is logged with the correlation ID; its message and stack trace never reach the response.
- Expected business outcomes are values, not exceptions (overview design, section 19.1); the endpoint maps them to a problem with a specific code.

### API contracts

The core's APIs are designed contract-first: the OpenAPI 3.1 documents in [`services/core/openapi/`](services/core/openapi) are the source of truth, and code follows them.

| File | Contract |
|---|---|
| [`v1.yaml`](services/core/openapi/v1.yaml) | Public `/v1` API, called by tenants' systems with an API key |
| [`internal.yaml`](services/core/openapi/internal.yaml) | `/internal` API, called by the console's BFF and the intelligence service with a service token |
| [`components.yaml`](services/core/openapi/components.yaml) | Building blocks both contracts reference: the `Problem` schema and the common error responses |

- A pull request that adds or changes an endpoint changes its contract first, in the same pull request.
- Operations reference shared building blocks instead of copying them, for example `$ref: components.yaml#/responses/Unauthorized`.
- [Spectral](https://docs.stoplight.io/docs/spectral) lints both contracts ([`.spectral.yaml`](.spectral.yaml)): the standard OpenAPI rules, plus paths under `/v1` or `/internal` with snake_case segments, snake_case properties, and error responses (`4xx`, `5xx`) only as `application/problem+json`. Warnings fail the lint.

```bash
just lint-api
```

### API security

Every request passes through [Spring Security](https://docs.spring.io/spring-security/reference/) before any controller runs, configured in one place: [`SecurityConfiguration`](services/core/src/main/java/dev/finaxis/core/access/SecurityConfiguration.java) in the `access` module.

- **Deny by default.** A request is allowed only if a rule opens it. Today the only rule opens the health endpoint (`/actuator/health` and its probes). A new endpoint answers `401` until its pull request adds an explicit rule, and that rule is reviewed like any other code.
- An unauthenticated request gets a `401` (code `unauthorized`); an authenticated one without a matching rule, a `403` (code `forbidden`). Both are Problem Details, and their detail never says why, so a missing path and a closed one look the same.
- **Stateless:** no HTTP session, no cookies, no login form, HTTP Basic or `/logout`. Callers will authenticate on every request with API keys or service tokens (phase 1). Without cookies there is nothing for CSRF to abuse, so CSRF protection is off; the BFF, which uses cookies, has its own.
- Every response, errors included, carries `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and `Cache-Control: no-store`. `Strict-Transport-Security` is sent on HTTPS requests only; TLS ends at the reverse proxy (overview design, section 4.2).

### Local infrastructure

Local services run in Docker Compose ([`infra/compose/compose.yaml`](infra/compose/compose.yaml)). The root [`justfile`](justfile) is the entry point; run `just` to list every recipe.

```bash
just up      # start the core profile and wait until it is healthy
just down    # stop it; data is kept in the postgres-data volume
```

| Profile | Services | Reachable at |
|---|---|---|
| `core` | PostgreSQL 17 (database and user `finaxis`) | `127.0.0.1:5432` |

Settings come from `infra/compose/.env`, which is git-ignored. `just up` creates it from [`.env.example`](infra/compose/.env.example) on first use; edit it to change the password or, if port 5432 is already taken on your machine, `FINAXIS_DB_PORT`.

- Ports are bound to `127.0.0.1`, so the services are not reachable from the network.
- Images are pinned by digest (`image: name:tag@sha256:...`).
- The `finaxis` user is the database owner, for local administration only. The service will connect with least-privilege roles, introduced with its first schemas.
- To start from an empty database, remove the volume: `docker compose --file infra/compose/compose.yaml --profile core down --volumes`.

### Container image

The core ships as a container image built from [`services/core/Dockerfile`](services/core/Dockerfile):

```bash
just image   # builds finaxis-core:dev
```

- **Multi-stage:** a JDK stage packages the JAR with Gradle; the final stage holds only the Java runtime and the application. Tests are not run in the image build; CI runs them in the Core workflow.
- **Distroless runtime** (`gcr.io/distroless/java25-debian13:nonroot`): no shell, no package manager, and the process runs as user `65532`, never root. To inspect a running container, use `docker logs` and the health endpoint; `docker exec` has nothing to run.
- **Layers by change frequency:** libraries, Spring Boot's loader and the service's code are separate layers, so a code change rebuilds only the last one.
- Base images are pinned by digest; Dependabot bumps tag and digest together.
- The image runs with the default profile: give it the database through `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. The image has no `HEALTHCHECK` (there is no shell or `curl` inside); orchestrators probe `/actuator/health/liveness` and `/actuator/health/readiness` over HTTP.

## Continuous integration

GitHub Actions runs these checks on every pull request and on `main` ([`.github/workflows/`](.github/workflows)):

| Workflow | Check |
|---|---|
| Core | `./gradlew build` in `services/core`: every [code quality gate](#code-quality-gates) and the tests; test reports are kept as an artifact when it fails |
| API contracts | Spectral lints the [API contracts](#api-contracts) |
| Core image | Builds the [container image](#container-image) and scans it with Trivy, also weekly: high or critical Dockerfile misconfigurations, and high or critical vulnerabilities that have a fix |
| Gitleaks | Scans the full git history for secrets |
| CodeQL | Static analysis (SAST) of workflows and Java code; findings appear under **Security → Code scanning** |

The Mutation workflow runs `./gradlew pitest` every night and on demand from the Actions tab, not on pull requests; its report is kept as an artifact.

The Core workflow validates the Gradle wrapper JAR against known checksums and caches Gradle dependencies; only `main` writes the cache.

Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml)) opens weekly, grouped update PRs for GitHub Actions, the root pnpm tooling, the core Gradle build, the core's base images and the local infrastructure images.

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
