#!/bin/sh

set -eu

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${PRICING_DB_USER:?PRICING_DB_USER is required}"
: "${PRICING_DB_PASSWORD:?PRICING_DB_PASSWORD is required}"

psql \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 \
    --set=pricing_user="$PRICING_DB_USER" \
    --set=pricing_password="$PRICING_DB_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'pricing_user',
    :'pricing_password'
)
WHERE NOT EXISTS (
    SELECT
    FROM pg_catalog.pg_roles
    WHERE rolname = :'pricing_user'
)
\gexec

SELECT format(
    'CREATE SCHEMA pricing AUTHORIZATION %I',
    :'pricing_user'
)
WHERE NOT EXISTS (
    SELECT
    FROM pg_catalog.pg_namespace
    WHERE nspname = 'pricing'
)
\gexec
SQL
