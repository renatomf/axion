# One command per task: run `just` to list them. https://just.systems

set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --file infra/compose/compose.yaml"

# List the available recipes
default:
    @just --list

# Start a profile of local infrastructure (core or auth) and wait until it is healthy
up profile="core": _env
    {{ compose }} --profile {{ profile }} up --detach --wait

# Stop every profile of local infrastructure (data is kept in its volumes)
down:
    {{ compose }} --profile "*" down

# Create infra/compose/.env from the example on first use
_env:
    @[ -f infra/compose/.env ] || { cp infra/compose/.env.example infra/compose/.env; echo "Created infra/compose/.env from .env.example"; }

# Lint the OpenAPI contracts of the core
lint-api:
    pnpm run lint:api

# Build the container image of the core service
image:
    docker build --tag finaxis-core:dev services/core
