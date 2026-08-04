#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_NAME="rentflow-pricing-smoke"
readonly BASE_URL="http://localhost:8080"
readonly SERIAL_NUMBER="SMOKE-001"
readonly EXPECTED_PRICING='{"serialNumber":"SMOKE-001","price":125.50,"weekendRate":1.2500,"longRentalCondition":7,"longRentalDiscount":0.1000,"deposit":300.00}'
readonly CREATE_REQUEST='{"serialNumber":"SMOKE-001","price":125.50,"weekendRate":1.2500,"longRentalCondition":7,"longRentalDiscount":0.1000,"deposit":300.00}'

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "$PROJECT_ROOT"

compose() {
    docker compose --project-name "$PROJECT_NAME" "$@"
}

cleanup() {
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

info() {
    printf '%s\n' "$*"
}

wait_for_service_health() {
    local service="$1"
    local max_attempts="$2"
    local attempt=1
    local container_id
    local health

    while ((attempt <= max_attempts)); do
        container_id="$(compose ps --quiet "$service")"
        if [[ -n "$container_id" ]]; then
            health="$(docker inspect \
                --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                "$container_id" 2>/dev/null || true)"
            if [[ "$health" == "healthy" ]]; then
                return
            fi
        fi
        sleep 1
        attempt=$((attempt + 1))
    done

    compose ps >&2 || true
    fail "$service did not become healthy within ${max_attempts}s"
}

wait_for_http_status() {
    local expected_status="$1"
    local url="$2"
    local max_attempts="$3"
    local attempt=1
    local actual_status

    while ((attempt <= max_attempts)); do
        actual_status="$(curl \
            --silent \
            --output /dev/null \
            --write-out '%{http_code}' \
            --max-time 2 \
            "$url" 2>/dev/null || true)"
        if [[ "$actual_status" == "$expected_status" ]]; then
            return
        fi
        sleep 1
        attempt=$((attempt + 1))
    done

    fail "$url did not return HTTP $expected_status within ${max_attempts}s"
}

assert_http_status() {
    local expected_status="$1"
    local url="$2"
    local actual_status

    actual_status="$(curl \
        --silent \
        --output /dev/null \
        --write-out '%{http_code}' \
        --max-time 2 \
        "$url" 2>/dev/null || true)"
    [[ "$actual_status" == "$expected_status" ]] \
        || fail "$url returned HTTP $actual_status instead of $expected_status"
}

assert_bootstrap_state() {
    local postgres_user="${POSTGRES_USER:-rentflow_admin}"
    local postgres_db="${POSTGRES_DB:-rentflow}"
    local pricing_user="${PRICING_DB_USER:-pricing}"
    local role_state
    local table_count

    role_state="$(compose exec --no-TTY rentflow-postgres \
        psql \
        --username "$postgres_user" \
        --dbname "$postgres_db" \
        --tuples-only \
        --no-align \
        --command "
            SELECT role.rolname
                   || ':' || role.rolcanlogin
                   || ':' || role.rolsuper
                   || ':' || role.rolcreatedb
                   || ':' || role.rolcreaterole
            FROM pg_catalog.pg_namespace AS namespace
            JOIN pg_catalog.pg_roles AS role ON role.oid = namespace.nspowner
            WHERE namespace.nspname = 'pricing';
        ")"
    [[ "$role_state" == "${pricing_user}:true:false:false:false" ]] \
        || fail "Pricing role/schema bootstrap state is invalid"

    table_count="$(compose exec --no-TTY rentflow-postgres \
        psql \
        --username "$postgres_user" \
        --dbname "$postgres_db" \
        --tuples-only \
        --no-align \
        --command "
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'pricing';
        ")"
    [[ "$table_count" == "0" ]] \
        || fail "The database bootstrap created application or migration tables"
}

assert_runtime_image() {
    local container_id
    local configured_user
    local entrypoint
    local healthcheck

    container_id="$(compose ps --quiet pricing)"
    configured_user="$(docker inspect --format '{{.Config.User}}' "$container_id")"
    entrypoint="$(docker inspect --format '{{json .Config.Entrypoint}}' "$container_id")"
    healthcheck="$(docker inspect --format '{{json .Config.Healthcheck.Test}}' "$container_id")"

    [[ "$configured_user" == "10001:10001" ]] \
        || fail "Pricing is not configured to run as UID/GID 10001"
    [[ "$entrypoint" == '["java","-jar","/opt/pricing/pricing.jar"]' ]] \
        || fail "Pricing does not use the expected runtime artifact"
    [[ "$healthcheck" == *'"http://localhost:8080/readyz"'* ]] \
        || fail "Pricing health does not depend on readiness"
    [[ "$healthcheck" != *"livez"* ]] \
        || fail "Pricing container health must not use liveness"

    compose exec --no-TTY pricing sh -ec '
        test "$(id -u)" = "10001"
        test "$(id -g)" = "10001"
        test -r /opt/pricing/pricing.jar
        ! command -v javac >/dev/null 2>&1
        ! command -v mvn >/dev/null 2>&1
        ! test -d /workspace
        ! test -d /root/.m2
        ! test -d /home/pricing/.m2
    ' || fail "Pricing runtime image contains build tooling or runs with the wrong identity"
}

create_pricing() {
    local response

    response="$(curl \
        --fail \
        --silent \
        --show-error \
        --max-time 5 \
        --request POST \
        --header 'Content-Type: application/json' \
        --data "$CREATE_REQUEST" \
        "${BASE_URL}/api/v1/pricing")"
    [[ "$response" == "$EXPECTED_PRICING" ]] || fail "Create response did not match the pricing contract"
}

assert_pricing_readable() {
    local response

    response="$(curl \
        --fail \
        --silent \
        --show-error \
        --max-time 5 \
        "${BASE_URL}/api/v1/pricing/${SERIAL_NUMBER}")"
    [[ "$response" == "$EXPECTED_PRICING" ]] || fail "Persisted pricing could not be retrieved"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v docker >/dev/null 2>&1 || fail "docker is required"
docker compose version >/dev/null 2>&1 || fail "Docker Compose is required"
command -v curl >/dev/null 2>&1 || fail "curl is required"

cleanup

info "Validating Compose configuration"
compose config --quiet

info "Building the Pricing runtime image"
compose build pricing

info "Starting PostgreSQL and validating first-run bootstrap ownership"
compose up --detach rentflow-postgres
wait_for_service_health rentflow-postgres 60
assert_bootstrap_state

info "Starting Pricing"
compose up --detach pricing
wait_for_service_health pricing 120
assert_runtime_image

info "Creating and retrieving pricing"
create_pricing
assert_pricing_readable

info "Restarting Pricing and checking database-backed persistence"
compose restart pricing
wait_for_service_health pricing 120
assert_pricing_readable

info "Stopping PostgreSQL and checking independent liveness"
compose stop rentflow-postgres
wait_for_http_status 503 "${BASE_URL}/readyz" 30
assert_http_status 200 "${BASE_URL}/livez"

info "Restarting PostgreSQL and checking readiness recovery"
compose start rentflow-postgres
wait_for_service_health rentflow-postgres 60
wait_for_http_status 200 "${BASE_URL}/readyz" 60
wait_for_service_health pricing 60
assert_pricing_readable

info "Recreating the stack without deleting its volume"
compose down --remove-orphans
compose up --detach
wait_for_service_health rentflow-postgres 60
wait_for_service_health pricing 120
assert_pricing_readable

info "Container smoke verification passed"
