# RentFlow Pricing Service

The Pricing service owns the commercial terms assigned to RentFlow equipment. Each pricing
resource contains:

- A unique, manually assigned, immutable equipment `serialNumber`.
- A positive, currency-agnostic daily `price` with at most two fractional digits.
- A positive `weekendRate` multiplier with at most four fractional digits.
- A positive whole-number `longRentalCondition` in calendar days.
- A fractional `longRentalDiscount` in `[0, 1)` with at most four fractional digits.
- A non-negative, currency-agnostic security `deposit` with at most two fractional digits.

The unauthenticated REST API supports create, retrieve, bounded list/sort, full replacement, and
permanent deletion. Rental-total calculation is intentionally outside this first service skeleton.

## Prerequisites

- Java 25.
- Docker Engine with the Docker Compose plugin. Docker is required for the PostgreSQL 18.4 local
  stack and Testcontainers integration tests.
- `curl` for the examples and container smoke test.
- Maven 3.9.x is optional because Maven Wrapper 3.3.4 is included and pinned to Maven 3.9.16.

Ports `8081`, `5432`, and `6379` must be available for the default local stack. Set
`PRICING_PORT`, `POSTGRES_PORT`, or `REDIS_PORT` before starting Compose if a host port must
differ. PostgreSQL and Redis bind only to the host loopback interface.

## Build

Use Maven Wrapper from the repository root:

```bash
./mvnw -B -ntp clean verify
```

Or use an installed Maven 3.9.x distribution:

```bash
mvn -B -ntp clean verify
```

The executable application is produced at `target/pricing.jar`.

## Run Locally

Start the local PostgreSQL and Redis dependencies:

```bash
docker compose up --detach --wait rentflow-postgres rentflow-redis
docker compose ps rentflow-postgres rentflow-redis
```

Then run the application on the host with the Pricing role:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/rentflow'
export SPRING_DATASOURCE_USERNAME='pricing'
export SPRING_DATASOURCE_PASSWORD='pricing-local'
./mvnw -B -ntp spring-boot:run
```

These are development-only defaults from `compose.yaml`. If `POSTGRES_PORT` is overridden, use the
same port in `SPRING_DATASOURCE_URL`.

Stop the dependencies while preserving the PostgreSQL volume:

```bash
docker compose down
```

## Run With Docker Compose

Build and start Pricing with local PostgreSQL and Redis:

```bash
docker compose up --build --detach --wait
docker compose ps
curl --fail --silent --show-error http://localhost:8081/readyz
```

Follow application logs:

```bash
docker compose logs --follow pricing
```

Stop the stack while preserving PostgreSQL data:

```bash
docker compose down
```

Permanently delete the local database volume:

```bash
docker compose down --volumes
```

The volume reset is destructive and cannot recover local Pricing data. It is also required before
changing first-run bootstrap credentials on an existing volume.

## Database Ownership

RentFlow microservices currently share the PostgreSQL database `rentflow`, but each service has its
own login role and schema. Pricing connects as the `pricing` role and owns only the `pricing`
schema and the objects inside it.

On first start of an empty local volume, `docker/postgres/init-pricing.sh` creates only the Pricing
role and schema prerequisites. Flyway creates application objects after the service connects.

| Setting | Development-only default |
| --- | --- |
| Database | `rentflow` |
| Bootstrap administrator | `rentflow_admin` |
| Bootstrap administrator password | `rentflow-admin-local` |
| Pricing role | `pricing` |
| Pricing role password | `pricing-local` |

These values are local defaults, not production credentials. Deployed environments must provision
the shared database, Pricing login, role-owned schema, and secrets outside this repository before
starting the service.

## Configuration

The application contains no datasource URL or credentials. Supply standard Spring Boot variables:

| Variable | Required | Purpose |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | Yes | JDBC URL for the shared `rentflow` database |
| `SPRING_DATASOURCE_USERNAME` | Yes | Pricing-owned login role |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Password for the Pricing role |
| `INVENTORY_BASE_URL` | For creation | URL of a reachable Inventory service; defaults to `http://inventory` |
| `REDIS_HOST` | No | Redis host; defaults to `localhost` |
| `REDIS_PORT` | No | Redis port; defaults to `6379` |
| `REDIS_CONNECT_TIMEOUT` | No | Redis connection timeout; defaults to `500ms` |
| `REDIS_COMMAND_TIMEOUT` | No | Redis command timeout; defaults to `200ms` |
| `PRICING_CACHE_TTL` | No | Fixed positive-entry TTL; defaults to `1h` |
| `SERVER_PORT` | No | HTTP port; defaults to `8080` |
| `JAVA_TOOL_OPTIONS` | No | JVM runtime options |

The Compose bootstrap also accepts `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
`PRICING_DB_USER`, `PRICING_DB_PASSWORD`, `POSTGRES_PORT`, `PRICING_PORT`, and `REDIS_PORT`.
Inside Compose, Pricing always reaches Redis at `rentflow-redis:6379`; the `REDIS_PORT` Compose
override changes only the optional loopback publication used for local inspection. The local
Redis instance is unauthenticated and disposable and is not a production topology.
This repository's Compose stack does not start Inventory. To create pricing through that stack,
set `INVENTORY_BASE_URL` to an Inventory address reachable from the Pricing container. Cross-service
smoke testing is maintained in the Common repository.

## Database Migrations

Flyway migrations live in `src/main/resources/db/migration` and follow
`V{n}__description.sql`. Flyway history is stored at `pricing.flyway_schema_history`; application
tables and migration history remain in the Pricing-owned schema.

Migrations are append-only. Never edit a shipped migration—add a new versioned migration. The
`pricing` schema must already exist and be owned by the application role. Startup fails if the
database, schema, or privileges are unavailable, a migration fails, or Hibernate validation finds
schema drift. Hibernate validates the schema but never creates or updates it.

## API Documentation and Health

With the service running:

| Resource | URL |
| --- | --- |
| Pricing API | `http://localhost:8081/api/v1/pricing` |
| Swagger UI | `http://localhost:8081/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8081/v3/api-docs` |
| Liveness | `http://localhost:8081/livez` |
| Actuator liveness | `http://localhost:8081/actuator/health/liveness` |
| Readiness | `http://localhost:8081/readyz` |
| Actuator readiness | `http://localhost:8081/actuator/health/readiness` |

Actuator exposes only health and hides component details. Liveness is independent of PostgreSQL;
readiness returns HTTP `503` when the database is unavailable. Redis is excluded from both probe
groups, so the service can start and remain ready while Redis is unavailable as long as
PostgreSQL is healthy.

API errors use RFC 9457 Problem Details with `application/problem+json`, stable `type` and `code`
values, and sorted field violations for validation failures.

## Authoritative and Cached Retrieval

Pricing provides an authoritative retrieval path and two cached retrieval paths:

- `GET /api/v1/pricing/{serialNumber}` always reads PostgreSQL. Use it when the caller must see
  the latest committed value.
- `GET /api/v1/pricing/{serialNumber}/cached` uses Redis cache-aside. Use it for frequent reads
  where bounded staleness is acceptable.
- `POST /api/v1/pricing/batch/cached` retrieves several serial numbers through that same
  per-serial cache. Its JSON body is an array of 1–100 serial strings, and its response is an
  array of the same six-field pricing objects.

The batch limit counts input elements before duplicates are removed. Duplicate serial numbers
are looked up and returned only once, in first-occurrence order; matching is case-sensitive.
If any serial is missing, the whole batch returns `404 PRICING_NOT_FOUND` instead of a partial
array. The batch creates no separate cache entry: it reads and warms the same per-serial keys
as the single-item cached endpoint.

The cached endpoint stores only successful pricing lookups. Missing serial numbers are not
cached, so each `404` is checked against PostgreSQL. A cached value has a fixed, non-renewing TTL:
the default is one hour, configured with `PRICING_CACHE_TTL`, and cache hits do not extend it.
The single-item endpoints return the same `PricingDTO` or Problem Details; the batch returns an
array of those DTOs or one Problem Details response. None exposes a cache-source field,
`X-Cache`, or `Age` header.

Successful create, replace, and delete operations evict only the affected key after the database
commit. Direct SQL changes, an eviction failure, a process failure after commit, or a cache fill
that overlaps a mutation can still leave an older value until its TTL expires. The authoritative
endpoint is the strict-freshness escape hatch for those cases.

Redis is an optional accelerator, not a source of truth. A Redis read, write, or eviction failure
is recorded and fails open; cached retrieval falls back to PostgreSQL, mutation results remain
unchanged, and readiness continues to depend on PostgreSQL rather than Redis. Short connection
and command timeouts bound this fallback cost.

Inspect the exact local key and remaining TTL after requesting an existing `DRILL-001` through the
cached endpoint:

```bash
docker compose exec rentflow-redis \
  redis-cli --raw GET 'rentflow:pricing:v1:pricingBySerial::DRILL-001'
docker compose exec rentflow-redis \
  redis-cli --raw TTL 'rentflow:pricing:v1:pricingBySerial::DRILL-001'
```

Redis has no persistence volume in the local stack; cache entries are expected to disappear when
its container is recreated.

## API Examples

With a reachable Inventory service that contains `DRILL-001`, create pricing for that item:

```bash
curl --fail-with-body --include \
  --request POST 'http://localhost:8081/api/v1/pricing' \
  --header 'Content-Type: application/json' \
  --data '{
    "serialNumber": "DRILL-001",
    "price": 125.50,
    "weekendRate": 1.2500,
    "longRentalCondition": 7,
    "longRentalDiscount": 0.1000,
    "deposit": 300.00
  }'
```

Retrieve it authoritatively by its case-sensitive serial number:

```bash
curl --fail-with-body \
  'http://localhost:8081/api/v1/pricing/DRILL-001'
```

Retrieve it through the one-hour cache for a high-volume read path:

```bash
curl --fail-with-body \
  'http://localhost:8081/api/v1/pricing/DRILL-001/cached'
```

Retrieve several prices through those same per-serial cache entries (after creating pricing
for each serial):

```bash
curl --fail-with-body \
  --request POST 'http://localhost:8081/api/v1/pricing/batch/cached' \
  --header 'Content-Type: application/json' \
  --data '["DRILL-001", "SAW-002", "DRILL-001"]'
```

Request the first ten resources ordered by descending daily price. Non-serial sorts use
`serialNumber ASC` as a deterministic tie-breaker:

```bash
curl --fail-with-body \
  'http://localhost:8081/api/v1/pricing?page=0&size=10&sort=price&direction=desc'
```

Fully replace its mutable pricing terms. The body serial number must exactly match the path:

```bash
curl --fail-with-body \
  --request PUT 'http://localhost:8081/api/v1/pricing/DRILL-001' \
  --header 'Content-Type: application/json' \
  --data '{
    "serialNumber": "DRILL-001",
    "price": 150.00,
    "weekendRate": 1.5000,
    "longRentalCondition": 14,
    "longRentalDiscount": 0.2500,
    "deposit": 450.00
  }'
```

Permanently delete it:

```bash
curl --fail-with-body \
  --request DELETE \
  --output /dev/null \
  --write-out '%{http_code}\n' \
  'http://localhost:8081/api/v1/pricing/DRILL-001'
```

## Verification

The required lifecycle is:

```bash
mvn -B -ntp clean verify
```

It compiles production and test code, enforces Java/Maven and dependency rules, checks Palantir
Java formatting, runs no-context unit and ArchUnit tests, and runs migration, full REST API, and
generated-OpenAPI integration tests against PostgreSQL 18.4 Testcontainers. Docker must be running;
tests and checks are not skipped.

Maven Wrapper executes the same lifecycle:

```bash
./mvnw -B -ntp clean verify
```

If Spotless reports formatting differences, apply the pinned formatter and rerun verification:

```bash
./mvnw -B -ntp spotless:apply
mvn -B -ntp clean verify
```

Run the local container smoke test with:

```bash
./scripts/container-smoke-test.sh
```

The smoke test starts Pricing, PostgreSQL, and Redis; checks liveness, readiness, and basic
read-only Pricing responses; then removes its isolated containers and volume. Cross-service
creation and persistence workflows are exercised in the Common repository.
