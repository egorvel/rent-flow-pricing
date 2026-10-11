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
| Readiness | `http://localhost:8081/readyz` |

Actuator exposes only health and Prometheus on a separate private listener, default `127.0.0.1:8086`
for host execution. Compose binds that listener inside the container and leaves it unpublished;
the application listener returns 404 for `/actuator/health` and `/actuator/prometheus`.
Both application probes hide component details. Liveness is independent of PostgreSQL;
readiness returns HTTP `503` when the database is unavailable. Redis is excluded from both probe
groups, so the service can start and remain ready while Redis is unavailable as long as
PostgreSQL is healthy.

API errors use RFC 9457 Problem Details with `application/problem+json`, stable `type` and `code`
values, and sorted field violations for validation failures.

## Observability

Pricing exposes Prometheus metrics, emits ECS JSON logs to stdout, and creates correlated HTTP,
JDBC connection/query and Redis command spans. Trace export is opt-in. Host execution exposes
`http://127.0.0.1:8086/actuator/prometheus` and `/actuator/health` privately; Compose permits the
same-network target `pricing:8086/actuator/prometheus` without publishing that port. Probes and
Actuator requests produce no server observations. Redis, Inventory and collectors are excluded
from readiness; PostgreSQL remains authoritative.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MANAGEMENT_SERVER_PORT` | `8086` | Private management port; host execution can override it |
| `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | Host binding; Compose sets `0.0.0.0` inside the container |
| `OBSERVABILITY_ENVIRONMENT` | `local` | Common metric, ECS and trace environment identity |
| `OBSERVABILITY_INSTANCE` | `HOSTNAME`, otherwise `standalone` | ECS service node and OTel `service.instance.id`; host execution can override it |
| `TRACING_EXPORT_ENABLED` | `false` | Enable asynchronous HTTP/protobuf OTLP trace export |
| `TRACING_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Full signal URL reachable from the running service |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | Trace sampling, configurable for deployment |

To export to a collector reachable from host execution:

```bash
export TRACING_EXPORT_ENABLED=true
export TRACING_OTLP_ENDPOINT=http://localhost:4318/v1/traces
./mvnw -B -ntp spring-boot:run
```

Compose forwards the export, endpoint, sampling and environment variables. It runs independently
of telemetry backends. Export uses a queue of 2048 spans, batches of up to 512, and two-second
connect/call timeouts. Collector absence, slow delivery or queue overflow may lose traces while
requests and readiness continue. OTLP log and metric export are disabled; logs use stdout and
metrics use Prometheus pull. HTTP server/client and both logical lookup timers have histogram
buckets sized for 1ms–10s; these bounds do not impose request deadlines.

Metric labels include `service="rent-flow-pricing"` and `environment`; Prometheus supplies target
`job` and `instance`. HTTP metrics use route-template `uri` labels. Runtime instance belongs to
the trace resource and ECS node; serials, raw URLs and trace IDs are excluded from metric labels.
W3C propagation continues incoming context through Inventory calls. Baggage is disabled.

### Cache and dependency metrics

| Prometheus series | Labels / counting unit |
| --- | --- |
| `cache_gets_total` | `cache="pricingBySerial"`, `result="hit\|miss"`; annotation-driven single-cache statistics |
| `pricing_cache_errors_total` | `operation="get\|put\|evict\|clear"`; every suppressed provider failure |
| `pricing_cache_batch_entries_total` | `outcome="hit\|miss"`; distinct requested batch entries |
| `pricing_cache_batch_errors_total` | `operation="read\|decode\|put"`; existing batch error boundaries |
| `pricing_cache_batch_size_count`, `_sum` | Distribution of distinct batch sizes |
| `pricing_cache_database_loads_total` | `mode="single\|batch"`, `reason="miss\|read_error\|decode_error\|mixed"`; logical repository load attempts |
| `pricing_cache_database_entries_total` | `mode`, `reason="miss\|read_error\|decode_error"`; distinct entries requested in those attempts |
| `pricing_cache_lookup_seconds_count`, `_sum`, `_bucket` | `mode`, `outcome="hit\|miss\|degraded\|not_found\|error"`; one full cached invocation |
| `pricing_inventory_lookup_seconds_count`, `_sum`, `_bucket` | `dependency="inventory"`, `operation="exists"`, bounded `outcome`; one complete lookup including retry waiting |
| `http_client_requests_seconds_count`, `_sum`, `_bucket` | Physical HTTP attempts with templated `uri`, `method`, `status`, `client_name`, `outcome` and `exception` |
| `resilience4j_circuitbreaker_calls_seconds_count` | `name="inventory"`, `kind`; final logical circuit results |
| `resilience4j_circuitbreaker_state` | `name="inventory"`, `state`; named circuit state gauge |
| `resilience4j_circuitbreaker_not_permitted_calls_total` | `name="inventory"`; circuit rejections |
| `resilience4j_retry_calls_total` | `name="inventory"`, `kind`; logical retry-sequence results |

Standard JVM, Hikari pool and Redis command meters remain available. Logical lookup
timers also include Micrometer's bounded `error` label; aggregate matching outcome series
across error types. Existing cache-manager identity labels remain unchanged.

Database counters count attempted work before the repository call, including missing rows and
failed database queries. A single load has one requested entry. A batch load counts once and may
request many distinct entries. For two hits, two misses and one invalid cached value, the batch
adds one `mixed` load and three entries: two `miss`, one `decode_error`. A whole MGET read failure
assigns `read_error` to all requested entries. Hits, authoritative reads and writes add no cache
database loads. A failed population or eviction does not itself add another database load.

Single-cache statistics exclude batch MGET; inspect batch entry meters separately. Generic cache
errors and batch errors overlap, so summing them does not count unique failures. Successful cache
lookup outcomes are `hit`, `miss`, or `degraded`; `not_found` and unexpected `error` take precedence.
Inventory outcomes are `exists`, `missing`, `invalid_reference`, `response_error`, `unavailable`,
`short_circuited`, or unexpected `error`. Expected 400/404 results do not mark the logical lookup
as failed. A retry sequence is one logical/circuit result and can contain two physical HTTP calls;
an open circuit produces a logical rejection and no HTTP attempt.

### Logs and investigations

ECS logs retain available `traceId`/`spanId`, original exception messages, causes and stacks
(default maximum 16384 characters and 64 throwable levels). SQL statement text and actual HTTP
paths remain diagnostic content. Automatic capture of HTTP bodies/headers/query strings, Redis
arguments/values and bound SQL parameters stays disabled. Native exception text and inline SQL
literals can still contain values; collection controls do not redact arbitrary exception text.
Application-defined diagnostic fields omit credentials and customer/order data.

Cache diagnostics emit the first WARN immediately and at most one per get/put/evict/clear operation
per instance per 60 seconds. Every error is counted. The next WARN includes `suppressedCount` for
intervening suppressed records; a quiet period produces no final summary. Fields include fixed
cache/operation identity and exception type, without keys or payload dumps. Lettuce reconnect
messages have their own framework behavior.

Inventory scheduled retries emit WARN with attempt, configured `waitMillis`, failure category
and exception; the logical trace includes `inventory.retry`. Final remote/protocol failures emit
one WARN. Circuit transitions emit WARN entering OPEN/FORCED_OPEN and INFO otherwise. Expected
400/404 responses, local duplicates and individual open-circuit rejections do not produce application
failure WARNs. Successful create/replace/delete has HTTP telemetry without result INFO logs;
unexpected application failures retain correlated ERROR diagnostics and generic public problems.

| Investigation | Starting point |
| --- | --- |
| Redis outage versus ordinary misses | `sum by (mode, reason) (rate(pricing_cache_database_entries_total{service="rent-flow-pricing"}[5m]))`; compare cache errors and Redis command traces |
| Batch pressure on PostgreSQL | Compare load rate with entry rate and batch-size `_sum / _count`; use entry reasons to expand mixed batches |
| Stale price after a write | Inspect `pricing_cache_errors_total{operation="evict"}` and correlated WARNs; compare authoritative/cached responses under the existing TTL contract |
| Slow Inventory-backed creation | Follow server → logical lookup → physical HTTP spans and retry events; compare logical and physical latency histograms |
| Open circuit | Inspect state/not-permitted meters and `pricing_inventory_lookup_seconds_count{outcome="short_circuited"}`; expect zero HTTP attempts |
| Correlated failure | In Loki, `{service="rent-flow-pricing",environment="local"} \| json`; filter `traceId` and open the same trace in Tempo |

For business HTTP latency, use route-template series in
`histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="rent-flow-pricing",uri=~"/api/v1/pricing.*"}[5m])))`.
Probes and management are excluded. A quiet dependency has no new lookup samples; its state gauge
and infrastructure metrics still matter.

### Common integration handoff

Common owns collection/storage, Grafana dashboards, provisioned alerts and real-service workflows.
The following changes remain in that repository:

1. Set Pricing's management address to `0.0.0.0`, keep port 8086 unpublished, and scrape
   `pricing:8086/actuator/prometheus` under job `pricing`, with the existing 15s interval/5s timeout.
2. Enable tracing with `TRACING_EXPORT_ENABLED=true` and
   `TRACING_OTLP_ENDPOINT=http://rentflow-alloy:4318/v1/traces`; set environment and sampling.
3. Retain Common's existing Docker labels `rentflow.observability.collect=true`, service and
   environment. Alloy keeps ECS JSON and only service/environment Loki stream labels;
   trace IDs, span IDs and runtime identity stay searchable fields. Common already discovers logs.
4. Provision a Pricing dashboard and alert rules, and verify real Pricing→Inventory W3C continuity,
   initial/retried calls and Redis-outage database fallback in Common's workflow.

Suggested initial alert conditions are tuning starting points, not service SLOs or rules deployed here:

| Signal | Initial condition |
| --- | --- |
| Metrics unavailable | `up{job="pricing"} == 0` for 2m |
| Business HTTP 5xx | >5% with at least 20 requests in 5m, sustained 5m |
| Slow business requests | p95 >2s with at least 20 requests in 5m, sustained 5m |
| Redis-driven database work | Positive read_error/decode_error entry rate for 2m |
| Failed invalidation | Any eviction-error increase over 5m; investigate TTL-bounded staleness |
| Inventory unavailable | Positive unavailable/short_circuited lookup rate for 2m |
| Persistently open circuit | OPEN/FORCED_OPEN state for 2m, including without incoming traffic |

Use matched business-route traffic for HTTP ratios and minimum-volume conditions. Contact points,
retention, infrastructure exporters and backend access controls belong to Common/deployment.

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
are looked up and returned only once, and matching is case-sensitive. Response order is not
guaranteed; use each result's `serialNumber` to match it to an item. If any serial is missing, the
whole batch returns `404 PRICING_NOT_FOUND` instead of a partial array. When several serials are
missing, which one is named in the problem response is not guaranteed. The batch creates no
separate cache entry: one bulk Redis read and, for misses, one bulk PostgreSQL read reuse and warm
the same per-serial keys as the single-item cached endpoint.

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
generated-OpenAPI integration tests against PostgreSQL 18.4 Testcontainers, plus real Redis,
Inventory HTTP-client tracing, ECS diagnostics and enabled-export collector-failure coverage. Docker must be running;
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
read-only Pricing responses and private management isolation; then removes its isolated containers and volume. Cross-service
creation and persistence workflows are exercised in the Common repository.
