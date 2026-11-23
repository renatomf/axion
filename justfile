# One command per task: run `just` to list them. https://just.systems

set shell := ["bash", "-euo", "pipefail", "-c"]

compose := "docker compose --file infra/compose/compose.yaml"

# List the available recipes
default:
    @just --list

# Start a profile of local infrastructure (core, auth or rails) and wait until it is healthy
up profile="core": _env _signing-key
    {{ compose }} --profile {{ profile }} up --detach --wait
    {{ compose }} --profile {{ profile }} run --rm core-database

# Stop every profile of local infrastructure (data is kept in its volumes)
down:
    {{ compose }} --profile "*" down

# Drop Keycloak's database so the realm is imported again (removes its users)
keycloak-reset: _env
    {{ compose }} --profile auth stop keycloak
    {{ compose }} --profile auth exec --no-TTY postgres dropdb --username=axion --if-exists --force keycloak
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

# Add a signing key pair for the rails simulator to infra/compose/.env, unless it has one
_signing-key:
    #!/usr/bin/env bash
    set -euo pipefail
    env=infra/compose/.env
    # A key per machine, never in the repository (rails simulator design,
    # section 6.4). Delete the three lines from .env to replace it.
    grep -q '^RAILS_SIMULATOR_SIGNING_PRIVATE_KEY=.' "$env" && exit 0
    private=$(openssl genpkey -algorithm ed25519 -outform DER | openssl base64 -A)
    public=$(openssl base64 -d -A <<< "$private" | openssl pkey -inform DER -pubout -outform DER | openssl base64 -A)
    {
        echo "RAILS_SIMULATOR_SIGNING_KEY_ID=rails-$(date -u +%Y-%m)"
        echo "RAILS_SIMULATOR_SIGNING_PRIVATE_KEY=$private"
        echo "RAILS_SIMULATOR_SIGNING_PUBLIC_KEY=$public"
    } >> "$env"
    echo "Added a rails simulator signing key pair to $env"

# List the dead publications of the local core: failed after their last attempt
dead-publications:
    {{ compose }} --profile core exec --no-TTY postgres psql --username=axion --dbname=axion -v ON_ERROR_STOP=1 \
        -c "SET ROLE axion_events" \
        -c "SELECT id, listener_id, event_type, publication_date FROM events.event_publication WHERE completion_date IS NULL AND status = 'FAILED' AND completion_attempts >= 10 ORDER BY publication_date"

# Start a dead publication's attempts over, once the cause of its failures is fixed
resubmit-publication publication listener:
    {{ compose }} --profile core exec --no-TTY postgres psql --username=axion --dbname=axion -v ON_ERROR_STOP=1 \
        -v publication='{{ publication }}' -v listener='{{ listener }}' <<< \
        "SET ROLE axion_events; SELECT events.resubmit_dead_publication(:'publication', :'listener') AS resubmitted;"

# Lint the OpenAPI contracts of the core and the rails simulator, after testing the project's lint rules
lint-api:
    pnpm run test:api-rules
    pnpm run lint:api

# Build the container image of a service (core or rails-simulator)
image service="core":
    docker build --tag axion-{{ service }}:dev services/{{ service }}

# Run the core's fault-injection suite against a fresh image of the rails simulator
fault-injection: (image "rails-simulator")
    cd services/core && ./gradlew faultInjectionTest
