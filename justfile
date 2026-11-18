# One command per task: run `just` to list them. https://just.systems

set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --file infra/compose/compose.yaml --profile core"

# List the available recipes
default:
    @just --list

# Start local infrastructure and wait until it is healthy
up: _env
    {{ compose }} up --detach --wait

# Stop local infrastructure (data is kept in its volume)
down:
    {{ compose }} down

# Create infra/compose/.env from the example on first use
_env:
    @[ -f infra/compose/.env ] || { cp infra/compose/.env.example infra/compose/.env; echo "Created infra/compose/.env from .env.example"; }

# Lint the OpenAPI contracts of the core
lint-api:
    pnpm run lint:api

# Build the container image of the core service
image:
    docker build --tag finaxis-core:dev services/core
