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

Ports `8080` and `5432` must be available for the default local stack. Set `POSTGRES_PORT` before
starting Compose if the host PostgreSQL port must differ.

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

Start only the local PostgreSQL dependency:

```bash
docker compose up --detach --wait rentflow-postgres
docker compose ps rentflow-postgres
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

Stop PostgreSQL while preserving its volume:

```bash
docker compose down
```

## Run With Docker Compose

Build and start the application and database:

```bash
docker compose up --build --detach --wait
docker compose ps
curl --fail --silent --show-error http://localhost:8080/readyz
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
| `SERVER_PORT` | No | HTTP port; defaults to `8080` |
| `JAVA_TOOL_OPTIONS` | No | JVM runtime options |

The Compose bootstrap also accepts `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
`PRICING_DB_USER`, `PRICING_DB_PASSWORD`, and `POSTGRES_PORT`. PostgreSQL is published only on the
host loopback interface.

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
| Pricing API | `http://localhost:8080/api/v1/pricing` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| Liveness | `http://localhost:8080/livez` |
| Actuator liveness | `http://localhost:8080/actuator/health/liveness` |
| Readiness | `http://localhost:8080/readyz` |
| Actuator readiness | `http://localhost:8080/actuator/health/readiness` |

Actuator exposes only health and hides component details. Liveness is independent of PostgreSQL;
readiness returns HTTP `503` when the database is unavailable.

API errors use RFC 9457 Problem Details with `application/problem+json`, stable `type` and `code`
values, and sorted field violations for validation failures.

## API Examples

Create pricing:

```bash
curl --fail-with-body --include \
  --request POST 'http://localhost:8080/api/v1/pricing' \
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

Retrieve it by its case-sensitive serial number:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/pricing/DRILL-001'
```

Request the first ten resources ordered by descending daily price. Non-serial sorts use
`serialNumber ASC` as a deterministic tie-breaker:

```bash
curl --fail-with-body \
  'http://localhost:8080/api/v1/pricing?page=0&size=10&sort=price&direction=desc'
```

Fully replace its mutable pricing terms. The body serial number must exactly match the path:

```bash
curl --fail-with-body \
  --request PUT 'http://localhost:8080/api/v1/pricing/DRILL-001' \
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
  'http://localhost:8080/api/v1/pricing/DRILL-001'
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

Run the isolated live-container acceptance test with:

```bash
./scripts/container-smoke-test.sh
```

The smoke test builds the production image, verifies restricted role/schema bootstrap and the
unprivileged runtime, exercises create/retrieve and persistence across restarts, checks independent
liveness and database-aware readiness during a PostgreSQL outage, and removes its isolated
containers and volume on exit.
