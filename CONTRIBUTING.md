# Contributing to Finaxis

How work is done in this repository: setup, commands, rules and workflow. Design decisions live in [`docs/design-docs/`](docs/design-docs) and [`docs/adr/`](docs/adr); this file links to them instead of repeating them.

## Setup

Development happens on Linux, or WSL2 with the repository on the Linux filesystem.

```bash
./scripts/check-toolchain.sh   # verify the toolchain
pnpm install                   # repository tooling and git hooks
```

Gitleaks and editorconfig-checker are single binaries: download them from their official GitHub releases, verify the published SHA-256 checksum, and put them on your `PATH` (e.g. `~/.local/bin`).

Commits must be signed (SSH or GPG): `main` rejects unsigned commits.

### Git hooks

[Lefthook](https://lefthook.dev) runs these before a commit exists ([`lefthook.yml`](lefthook.yml)):

| Hook | Check |
|---|---|
| `pre-commit` | Gitleaks scans staged changes for secrets |
| `pre-commit` | editorconfig-checker validates staged files against [`.editorconfig`](.editorconfig) |
| `commit-msg` | commitlint validates the message against [Commits](#commits) |

Never bypass a hook with `--no-verify`; fix a false positive in the hook's configuration, in a pull request.

## Core service

[`services/core`](services/core) builds with the Gradle wrapper, which downloads the pinned Gradle version and verifies its checksum.

```bash
cd services/core
./gradlew build           # compile, every quality gate, the tests, and build/libs/finaxis-core.jar
./gradlew spotlessApply   # format the code before committing
./gradlew bootRun         # start the service with the local profile
./gradlew pitest          # mutation testing of the ledger, a few minutes; not part of build
```

- **Tests** use JUnit, AssertJ and, for rules that must hold for every input, [jqwik](https://jqwik.net) properties. Tests that start the application run against a real PostgreSQL, and member authentication tests against a real Keycloak, both started by Testcontainers: Docker must be running; `just up` is not needed. Reports: `build/reports/tests/test/index.html`.
- **Mutation testing** ([PIT](https://pitest.org)) fails when the ledger's tests catch fewer than 90% of the mutations; survivors are listed in `build/reports/pitest/index.html`.
- **Versions** are declared only in [`gradle/libs.versions.toml`](services/core/gradle/libs.versions.toml); Spring Boot's bill of materials manages the versions of its libraries. To upgrade Gradle, run `./gradlew wrapper --gradle-version <version> --gradle-distribution-sha256-sum <checksum>` with the checksum from [gradle.org/release-checksums](https://gradle.org/release-checksums/), then run it again so the wrapper JAR and scripts are updated too.

### Quality gates

`./gradlew build` fails on any of these:

| Gate | Tool | Fails on |
|---|---|---|
| Formatting | Spotless, Palantir Java Format | Unformatted code or unused imports |
| Bug patterns | Error Prone | Common Java mistakes, in main and test code |
| Null safety | NullAway, JSpecify | A possible null dereference: types are non-null unless `@Nullable`, through `@NullMarked` in each `package-info.java` |
| Zero warnings | `javac -Xlint:all -Werror` | Any javac or Error Prone warning |
| Module structure | Spring Modulith, `ModuleStructureTest` | A cycle between modules, or a module using a type another does not expose |
| Coding rules | ArchUnit, `ArchitectureTest` | Field injection; Spring or persistence in `domain`; the system clock instead of an injected `Clock`; an endpoint without a declared permission |

A `@SuppressWarnings` needs a comment saying why.

### Modules

The core is a modular monolith ([ADR 0002](docs/adr/0002-modular-monolith.md)): each direct subpackage of `dev.finaxis.core` is a module. A module exposes only the types in its own package. A module with business logic splits the rest into `domain` (plain Java), `application` (the implementation of its API, which owns the transactions) and `persistence` (the SQL), as `ledger` does. `shared` is open to every module. A new module is a new subpackage with a `package-info.java`.

### Comments

Code follows the [comment standard](docs/architecture/engineering-standards.md#comments-and-documentation): Javadoc on module APIs, comments only for a reason the code cannot state, nothing that explains the language or a framework.

### Configuration

[`application.yml`](services/core/src/main/resources/application.yml) holds safe defaults; `application-<profile>.yml` overrides them.

| Profile | Active when | Differences |
|---|---|---|
| default | No profile is set, as in the container image | Database from `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` (`finaxis_app`), `SPRING_DATASOURCE_PASSWORD`; migrations as the schemas' owner from `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`; Keycloak realm from `FINAXIS_MEMBERS_ISSUER`, required |
| `local` | `./gradlew bootRun`, or `SPRING_PROFILES_ACTIVE=local` | The PostgreSQL of `just up` and the Keycloak of `just up auth`, with the settings of `infra/compose/.env`; detailed health; plain-text logs |

The service listens on port 8080. Only the health endpoint is exposed (`/actuator/health`, `/liveness`, `/readiness`); exposing another Actuator endpoint is a security decision and needs a reason in the pull request.

### Database migrations

[Flyway](https://documentation.red-gate.com/flyway) applies the SQL files of [`db/migration`](services/core/src/main/resources/db/migration) in version order at startup.

- Name files `V<version>__<description>.sql`; a name Flyway cannot parse fails the build.
- Migrations are forward-only: an applied one is never edited or deleted.
- Breaking changes follow expand/contract: add the new structure, move the code, remove the old in a later migration.
- Each module creates its own schema and database role; `public` is closed to every role but the owner.

### Logging

Logs are JSON, one object per line, in Spring Boot's Elastic Common Schema format; the `local` profile writes plain text. Every line written during a request carries `correlation_id`, taken from a valid `X-Correlation-Id` header (1 to 64 letters, digits, `.`, `_` or `-`) or generated, and returned in the same header.

Log through SLF4J, never `System.out`. Never log secrets, credentials or personal data (CPF, CNPJ, names, Pix keys), although all data here is synthetic.

### APIs

The APIs are contract-first: the OpenAPI 3.1 documents in [`services/core/openapi/`](services/core/openapi) are the source of truth.

| File | Contract |
|---|---|
| [`v1.yaml`](services/core/openapi/v1.yaml) | Public `/v1` API, for tenants' systems with an API key |
| [`internal.yaml`](services/core/openapi/internal.yaml) | `/internal` API, for the console's BFF with a member's access token |
| [`components.yaml`](services/core/openapi/components.yaml) | What both reference: the `Problem` schema and the common error responses |

- A pull request that adds or changes an endpoint changes its contract first.
- Errors are RFC 9457 Problem Details, built by [`ApiExceptionHandler`](services/core/src/main/java/dev/finaxis/core/web/ApiExceptionHandler.java), each with a stable snake_case `code` clients branch on. An unknown exception is a `500` whose details are only logged. Expected business outcomes are values, mapped by the endpoint to a problem with its own code.
- Request bodies are records with Bean Validation constraints, taken as `@Valid @RequestBody`: an unreadable body is `400 bad_request`, a broken constraint `422 validation_failed` with the offending fields. Rejected values are never echoed.
- Endpoint tests check every response against its contract with [`OpenApiContract`](services/core/src/test/java/dev/finaxis/core/OpenApiContract.java), strictly: a property the contract does not list fails the test.
- A new operation needs a cross-tenant attempt in [`TenantIsolationTest`](services/core/src/test/java/dev/finaxis/core/TenantIsolationTest.java), which reads the operations from the contracts and fails for one it cannot attempt.
- `just lint-api` runs [Spectral](https://docs.stoplight.io/docs/spectral) ([`.spectral.yaml`](.spectral.yaml)); warnings fail.

### Security rules

How authentication and authorization work is in the [tenancy and access design](docs/design-docs/2026-11-19-tenancy-and-access.md), sections 4 and 5. A contribution must:

- **Open nothing by default.** Every request is denied unless a rule of [`SecurityConfiguration`](services/core/src/main/java/dev/finaxis/core/access/security/SecurityConfiguration.java) opens it; a new rule is reviewed like any other code.
- **Declare each endpoint's permission** with `@RequiresPermission(…)`, or `@NoPermissionRequired(reason = …)` when an authenticated caller is enough. `@RequiresStepUp` goes beside `@RequiresPermission` on operations that need a recent login.
- **Take the tenant from the caller only:** a tenant-scoped method takes a `Caller`, `MemberCaller` or `ApiKeyCaller` parameter and never reads the tenant from the request.
- **Never write an API key**, or anything shaped like one, in the repository: [`.gitleaks.toml`](.gitleaks.toml) rejects the format, and tests generate their keys at run time.

## Local infrastructure

Local services run in Docker Compose ([`infra/compose/compose.yaml`](infra/compose/compose.yaml)), through the root [`justfile`](justfile); `just` lists every recipe.

```bash
just up               # the core profile: PostgreSQL 17 on 127.0.0.1:5432
just up auth          # the auth profile: PostgreSQL and Keycloak 26 on http://localhost:8180
just down             # stop every profile; data stays in the postgres-data volume
just keycloak-reset   # re-import the realm file, dropping Keycloak's database and its users
just image            # build the container image finaxis-core:dev
```

- Settings come from the git-ignored `infra/compose/.env`. `just up` creates it from [`.env.example`](infra/compose/.env.example) and adds settings the example gained later, never changing existing values. Change `FINAXIS_DB_PORT` or `KEYCLOAK_PORT` there if a port is taken.
- Ports are bound to `127.0.0.1`, and images are pinned by digest.
- The `finaxis` database user is the owner, for local administration only; the service connects with the modules' least-privilege roles.
- To start from an empty database: `docker compose --file infra/compose/compose.yaml --profile "*" down --volumes`.

### Keycloak

Keycloak authenticates people ([ADR 0005](docs/adr/0005-keycloak-for-human-identity.md)); the realm's rules are in the tenancy design, section 4.1.

- The `finaxis` realm is code: [`infra/keycloak/realms/finaxis.json`](infra/keycloak/realms/finaxis.json). Change it there, never only in the admin console, and apply it with `just keycloak-reset`. Its client secret is a placeholder filled from `.env`.
- The admin console is `http://localhost:8180/admin`, user `admin` with `KEYCLOAK_ADMIN_PASSWORD`, for local use only.
- To try a login, add a user in realm `finaxis` (**Users**, **Add user**, a password under **Credentials**) and open `http://localhost:8180/realms/finaxis/account`; Keycloak asks for a TOTP app at the first login. Use synthetic names and emails only.

### Container image

[`services/core/Dockerfile`](services/core/Dockerfile) builds a multi-stage image whose runtime is distroless (`gcr.io/distroless/java25-debian13:nonroot`): no shell, and the process runs as user `65532`. It runs with the default profile, so it needs the database variables above; orchestrators probe `/actuator/health/liveness` and `/readiness` over HTTP, since the image has no `HEALTHCHECK`.

## Continuous integration

GitHub Actions runs on every pull request and on `main` ([`.github/workflows/`](.github/workflows)):

| Workflow | Check |
|---|---|
| Core | `./gradlew build`: every quality gate and the tests |
| API contracts | Spectral lints the contracts |
| Core image | Builds the image and scans it with Trivy, also weekly |
| Gitleaks | Scans the full history for secrets |
| CodeQL | Static analysis of workflows and Java code |

Mutation testing runs nightly and on demand, not on pull requests. Dependabot opens weekly grouped updates ([`.github/dependabot.yml`](.github/dependabot.yml)).

Workflow rules: actions pinned to a full commit SHA with the version in a comment; read-only permissions by default, a job asking only for what it needs; downloaded tools verified against their published checksum.

Security vulnerabilities are reported privately; see [`SECURITY.md`](SECURITY.md).

## Workflow

1. **Issue:** one per pull request, in the milestone of its phase.
2. **Branch:** from an up-to-date `main`, named `<type>/<short-description>`, such as `feat/ledger-domain`.
3. **Commits:** small, one concept each (see [Commits](#commits)).
4. **Pull request:** from the template, with `Closes #<issue>`.
5. **Review:** fixes are new commits; history is not rewritten during review.
6. **Merge:** the branch is rebased locally onto `main` and merged with a merge commit. GitHub's "rebase and merge" drops commit signatures, so it is not used.

A significant feature starts with a design doc pull request.

## Pull request size

- One pull request delivers one roadmap item, complete: code, tests and docs together.
- An item is split into slices only for a reason, named in the issue of its first slice:
  - a slice prepares the ground and lowers the risk of the next, such as tests rebound before a policy is forced;
  - a slice is a security boundary worth reviewing on its own, such as signature verification or SSRF validation;
  - migrations or deployment must happen in steps, such as expand and contract;
  - one pull request would be too large to review with care.
- Every slice leaves `main` working and delivers something usable: no code merged that nothing uses yet.
- There is no line-count target. Each commit introduces one concept, however many the pull request has.
- Generated or verbatim third-party text, such as lockfiles, does not count, but is called out in the description.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <imperative summary, lower case, no period>

<optional body: what and why, wrapped at 72 characters>
```

Types: `feat`, `fix`, `docs`, `test`, `refactor` (neither a fix nor a feature), `build`, `ci`, `chore`.

## Definition of Done

- [ ] Tests cover the change and pass.
- [ ] Documentation affected by the change is updated.
- [ ] The security checklist of the pull request template is complete.
- [ ] CI is green.
- [ ] The diff can be reviewed with care; a split, if any, has its reason in the issue.

## Language

Code, documentation, commits, issues and pull requests are in **English**. Domain terms that are proper names stay in Portuguese (`Pix`, `Boleto`, `Cnab`).

## AI assistance

This project is built with AI assistance. AI-generated changes go through the same workflow and review as any other; the author reviews every change before merging. Instructions for the assistant are in [`CLAUDE.md`](CLAUDE.md).
