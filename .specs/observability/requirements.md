# Pricing observability requirements

Status: Implementation in progress, authorized by the subsequent "Let's implement it now" request.

## Context

Pricing owns application instrumentation; Common owns telemetry collection, storage, dashboards,
alerts and cross-service verification. This feature applies the shared
[observability plan](../../../OBSERVABILITY_PLAN.md) and Inventory's implemented baseline to Pricing.

Actuator already supplies health and Micrometer. Single-cache statistics, batch entry/error/size
meters and Inventory Resilience4j meters exist, but Prometheus export, distributed tracing and ECS
logging are not configured. Batch misses combine ordinary misses and cache failures; suppressed
cache errors are logged only at DEBUG without exception details. Operators cannot directly measure
the database work caused by these paths or the full latency of an Inventory retry sequence.

## User stories with acceptance criteria

### US1 — Diagnose service availability and latency

As an operator, I need private metrics and correlated logs/traces without changing business behavior.

- **AC1.1 (Ubiquitous):** The service shall expose only health and Prometheus on a configurable management listener, default port 8086 and loopback address for host execution, with no management host-port publication in Compose.
- **AC1.2 (Ubiquitous):** The service shall preserve application-port liveness/readiness probes, hidden health details, database-only readiness, business/OpenAPI contracts, cache behavior and existing transaction boundaries.
- **AC1.3 (Ubiquitous):** The service shall identify telemetry by service, environment and runtime instance, using bounded metric/Loki labels and excluding business identifiers, trace IDs and raw URLs from those labels.
- **AC1.4 (Event-driven):** When a business HTTP request performs database or Redis work, the service shall produce correlated request and applicable JDBC connection/query or Redis command spans, HTTP latency histograms and existing JVM/pool metrics.
- **AC1.5 (Unwanted):** If a trace collector is absent, slow or unable to accept queued spans, then the service shall preserve business responses and readiness independently of export, with bounded asynchronous export and trace export disabled by default.

### US2 — Explain cache effectiveness and degradation

As an operator, I need to distinguish normal cache population from Redis-driven database fallback.

- **AC2.1 (Ubiquitous):** The service shall export existing single-cache statistics and cache error/batch meters without changing their names or counting meanings.
- **AC2.2 (Event-driven):** When a cached single or batch read attempts a PostgreSQL load, the service shall count the load attempt once and count its distinct requested entries by ordinary-miss, Redis-read-error or cache-decode-error reason, including mixed-reason batches and loads that later fail or return not-found.
- **AC2.3 (Event-driven):** When cached retrieval completes, the service shall observe its full duration and bounded outcome, distinguishing successful hits, ordinary database loads, successful degraded retrieval, not-found and unexpected failures.
- **AC2.4 (Event-driven):** When a cache get, put, eviction or clear failure is suppressed, the service shall retain fail-open behavior and count every failure while emitting structured WARN exception diagnostics at most once per operation per instance in each 60-second interval, including the number suppressed since the preceding WARN in the next emitted WARN.
- **AC2.5 (Unwanted):** If cache population or post-commit eviction fails, then the service shall report that failed operation without counting a new database fallback solely because of that failure, and shall preserve committed writes and existing TTL-bounded staleness.

### US3 — Investigate Inventory dependency behavior

As an operator, I need to connect Inventory latency, retries and circuit rejection to Pricing requests.

- **AC3.1 (Event-driven):** When Pricing performs an Inventory lookup, the service shall observe one logical lookup covering circuit evaluation, all physical attempts and retry waiting, while propagating valid W3C context through the existing HTTP client to each attempted Inventory request.
- **AC3.2 (Event-driven):** When an Inventory lookup completes or is rejected, the service shall record a bounded logical outcome and retain existing retry predicates, circuit-breaker decorator order, meter meanings, exception translation and HTTP status mapping.
- **AC3.3 (Event-driven):** When an Inventory retry is scheduled or its circuit changes state, the service shall emit corresponding structured diagnostics, add an observation event for each scheduled retry when a logical observation is active, and avoid additional attempts or dependency checks for telemetry.

### US4 — Retain useful diagnostics and deliver a verifiable integration contract

As an implementer, I need useful internal diagnostics and a concrete handoff to Common.

- **AC4.1 (Ubiquitous):** The service shall emit ECS JSON to stdout with available trace/span correlation and ordinary exception messages, cause chains and stacks, while keeping automatic HTTP body/header/query-string, Redis argument/value and bound SQL parameter capture disabled and excluding credentials and customer/order data from application-defined diagnostic fields.
- **AC4.2 (Ubiquitous):** The service shall use HTTP metrics/traces for successful writes and expected validation/conflict/not-found results, emit application diagnostics for unexpected failures and dependency/cache resilience events, and avoid per-success write INFO logs.
- **AC4.3 (Ubiquitous):** The service shall document scrape/export/log configuration, metric meanings, bounded labels, investigation examples, proposed alert signals and Common's remaining integration work while remaining independently runnable.
- **AC4.4 (Ubiquitous):** The implementation shall pass Java 25 Maven clean verify and the existing basic Pricing container smoke test, including management isolation, propagation, cache accounting, diagnostic capture controls, exporter outage and persistence regressions.

## Out of scope

Common configuration changes or
dashboard/alert provisioning; production authentication/TLS and SLO selection; database migrations;
cache correctness fixes, durable eviction, stampede control or retry/circuit policy changes;
Kafka instrumentation; calculated rental-price features; an OpenTelemetry agent or parallel SDK;
universal text redaction and blanket framework telemetry allowlists; service fixtures or cross-service
creation workflows in Pricing's Compose smoke test.

## Resolved questions

1. Create a full requirements/design/tasks spec first; the subsequent implementation request
   authorizes Pricing implementation.
   See design.md §1 and tasks.md §1.
2. Include Redis command spans alongside HTTP and JDBC spans, with command arguments and values
   excluded through Boot/Lettuce defaults. See design.md §3.
3. Distinguish ordinary misses from Redis failures for single and batch database loads, preserving
   existing meters and counting mixed batches explicitly. See design.md §4.
4. Observe the complete logical Inventory lookup, including retry waiting, alongside physical HTTP
   attempts and existing Resilience4j meters. See design.md §5.
5. Emit rate-limited WARN cache diagnostics with ordinary exception details; counters remain exact.
   The initial limit is one WARN per operation per instance per 60 seconds. See design.md §6.
6. Use traces/metrics for successful writes; application logs describe failures and resilience events.
   See design.md §6.
