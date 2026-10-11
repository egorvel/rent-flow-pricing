#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_NAME="rentflow-pricing-smoke"
readonly BASE_URL="http://localhost:${PRICING_PORT:-8081}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

cd "$PROJECT_ROOT"

compose() {
    docker compose --project-name "$PROJECT_NAME" "$@"
}

cleanup() {
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}

assert_http_status() {
    local expected_status="$1"
    local url="$2"
    local actual_status

    actual_status="$(curl \
        --silent \
        --output /dev/null \
        --write-out '%{http_code}' \
        --max-time 5 \
        "$url")"
    if [[ "$actual_status" != "$expected_status" ]]; then
        printf 'ERROR: %s returned HTTP %s instead of %s\n' "$url" "$actual_status" "$expected_status" >&2
        exit 1
    fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v docker >/dev/null 2>&1 || { printf 'ERROR: docker is required\n' >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { printf 'ERROR: curl is required\n' >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { printf 'ERROR: Docker Compose is required\n' >&2; exit 1; }

compose config --quiet
compose up --build --detach --wait --wait-timeout 120

assert_http_status 200 "${BASE_URL}/livez"
assert_http_status 200 "${BASE_URL}/readyz"
assert_http_status 200 "${BASE_URL}/api/v1/pricing"
assert_http_status 404 "${BASE_URL}/api/v1/pricing/SMOKE-MISSING"
assert_http_status 404 "${BASE_URL}/api/v1/pricing/SMOKE-MISSING/cached"
assert_http_status 404 "${BASE_URL}/actuator/prometheus"
assert_http_status 404 "${BASE_URL}/actuator/health"

pricing_container="$(compose ps --quiet pricing)"
management_binding="$(docker inspect \
    --format '{{with index .HostConfig.PortBindings "8086/tcp"}}{{json .}}{{end}}' \
    "$pricing_container")"
if [[ -n "$management_binding" ]]; then
    printf 'ERROR: management port 8086 is published to the host\n' >&2
    exit 1
fi
compose exec --no-TTY pricing curl \
    --fail --silent --show-error --max-time 5 \
    http://127.0.0.1:8086/actuator/prometheus >/dev/null

printf 'Container smoke verification passed\n'
