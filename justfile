# One command per task: run `just` to list them. https://just.systems

set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --file infra/compose/compose.yaml"

# List the available recipes
default:
    @just --list

# Start a profile of local infrastructure (core or auth) and wait until it is healthy
up profile="core": _env
    {{ compose }} --profile {{ profile }} up --detach --wait
    {{ compose }} --profile {{ profile }} run --rm core-database

# Stop every profile of local infrastructure (data is kept in its volumes)
down:
    {{ compose }} --profile "*" down

# Drop Keycloak's database so the realm is imported again (removes its users)
keycloak-reset: _env
    {{ compose }} --profile auth stop keycloak
    {{ compose }} --profile auth exec --no-TTY postgres dropdb --username=finaxis --if-exists --force keycloak
    {{ compose }} --profile auth up --detach --wait

# Create infra/compose/.env from the example, or add the settings it lacks
_env:
    #!/usr/bin/env bash
    set -euo pipefail
    example=infra/compose/.env.example env=infra/compose/.env
    [ -f "$env" ] || { cp "$example" "$env"; echo "Created $env from .env.example"; exit 0; }
    # Settings added to the example later are appended with their example
    # values; values already in .env are never changed.
    grep -E '^[A-Z_]+=' "$example" | while IFS='=' read -r key _; do
        grep -q "^$key=" "$env" || { grep "^$key=" "$example" >> "$env"; echo "Added $key to $env"; }
    done

# Lint the OpenAPI contracts of the core
lint-api:
    pnpm run lint:api

# Build the container image of the core service
image:
    docker build --tag finaxis-core:dev services/core
