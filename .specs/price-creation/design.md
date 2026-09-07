# Price Creation Design

Status: Implemented except isolated container acceptance; tracked by `tasks.md` T2.

This document implements `requirements.md`. Numbered sections are stable traceability targets.
Shared foundations are linked to the Service Skeleton instead of copied.

## 1. Scope and architecture

### 1.1 Specification ownership

This feature owns the `POST /api/v1/pricing` use case from validated request through Inventory
verification and persistence. The [Service Skeleton design](../service-skeleton/design.md) remains
authoritative for:

- the `PricingDTO` shape and shared field constraints in §2.3 and §5.1;
- the entity, repository, schema, and database constraints in §4;
- the generic RFC 9457 envelope in §6.1;
- common OpenAPI generation in §7; and
- runtime, database, health, container, and verification foundations in §8-§11.

This document is authoritative for creation ordering, Inventory communication, retry and
circuit-breaker behavior, creation-specific Problem Details entries, and creation-specific
OpenAPI responses. Retrieval, listing, replacement, deletion, and price calculation are not
redefined here.

### 1.2 Components and request flow

| Package | Types | Creation responsibility |
| --- | --- | --- |
| `com.rentflow.controller` | `PricingController`, `ApiExceptionHandler` | Bind `POST` and translate failures |
| `com.rentflow.converter` | `PricingConverter` | Convert the shared DTO and entity |
| `com.rentflow.service` | `PricingService`, `InventoryGateway`, creation exceptions | Orchestrate local and remote decisions |
| `com.rentflow.service.rest` | `RestInventoryService`, `InventoryHttpClient`, `InventoryRetryableExceptionPredicate` | Implement the outbound Inventory adapter |
| `com.rentflow.config` | `InventoryHttpClientConfig` | Import the named HTTP-service client |
| `com.rentflow.repository` | `PricingRepository` | Check identity and perform the final insert |

The implemented flow is:

```text
POST JSON
  -> Spring JSON binding and Bean Validation
  -> PricingController
  -> PricingConverter
  -> PricingService
       1. PricingRepository.existsById
       2. InventoryGateway.exists
            -> RestInventoryService
                 -> CircuitBreaker("inventory")
                      -> Retry("inventory")
                           -> InventoryHttpClient GET
       3. PricingRepository.saveAndFlush
  -> PricingConverter
  -> 201 response
```

The service depends on the `InventoryGateway` port rather than the HTTP adapter. This keeps
creation orchestration independently unit-testable and keeps HTTP exceptions out of
`PricingService`.

### 1.3 Runtime dependencies

The feature adds these focused dependencies to the shared Maven baseline:

| Dependency | Role |
| --- | --- |
| `spring-boot-starter-restclient` | Spring HTTP Service client backed by synchronous `RestClient` infrastructure |
| `resilience4j-bom:2.4.0` | One version source for Resilience4j modules |
| `resilience4j-spring-boot4:2.4.0` | Spring Boot 4 configuration, registries, auto-configuration, and metrics integration |
| `resilience4j-circuitbreaker` | Circuit-breaker core API used by the adapter |
| `resilience4j-retry` | Retry core API used by the adapter |

`resilience4j-spring-boot4` transitively supplies `resilience4j-micrometer`. The application
therefore does not declare `resilience4j-micrometer` directly. The shared Actuator starter
supplies Micrometer's registry infrastructure. Removing the duplicate direct declaration keeps
one dependency path without disabling metric registration.

## 2. HTTP creation contract

### 2.1 Request and shared validation

`POST /api/v1/pricing` consumes `application/json` and accepts the shared `PricingDTO`.
Its six required fields, numeric bounds, scale limits, serial-number pattern, unknown-property
rejection, and deterministic validation violations are defined once in the Service Skeleton
design §2.3, §5.1, and §6.1.

Spring parses JSON and runs Bean Validation before the controller invokes `PricingService`.
Consequently malformed, wrong-type, incomplete, unknown-property, and constraint-violating
requests cannot reach the local duplicate check or Inventory.

### 2.2 Successful response

After Inventory confirms the serial number and PostgreSQL accepts the insert, the controller
returns:

- `201 Created`;
- `Content-Type: application/json`;
- all six persisted values as `PricingDTO`; and
- `Location: /api/v1/pricing/{serialNumber}`.

The serial alphabet from the shared constraint is safe as one URI path segment, so the controller
can construct `Location` without lossy normalization. Serial-number comparison and persistence
remain case-sensitive.

### 2.3 Result matrix

| Condition | Pricing status | Problem code or body |
| --- | --- | --- |
| Valid body, unused local serial, Inventory `200`, insert succeeds | `201` | Created `PricingDTO` |
| Shared request validation or malformed JSON | `400` | Shared validation or malformed-JSON problem |
| Inventory `400` | `400` | `INVALID_INVENTORY_REFERENCE` |
| Existing local pricing or uniqueness race | `409` | `PRICING_ALREADY_EXISTS` |
| Inventory `404` | `422` | `INVENTORY_ITEM_NOT_FOUND` |
| Other Inventory `4xx` or unexpected normal response | `502` | `INVENTORY_SERVICE_ERROR` |
| Resource-access failure, any Inventory `5xx` after retry policy, or open circuit | `503` | `INVENTORY_SERVICE_UNAVAILABLE` |
| Unsupported response representation | `406` | Shared not-acceptable problem |
| Unsupported request representation | `415` | Shared unsupported-media-type problem |
| Unexpected application failure | `500` | Shared sanitized internal-error problem |

`400` is reserved for a malformed or invalid Pricing request and for Inventory's explicit
judgment that the serial reference itself is invalid. `404` from Inventory is not exposed as
Pricing `404` because the requested Pricing collection exists and the submitted representation
cannot be processed; it therefore maps to `422`. Other Inventory protocol errors describe a
bad upstream response rather than a bad Pricing request and therefore map to `502`.

### 2.4 Generic failures

The generic Problem Details shape, validation sorting, malformed JSON, media negotiation, method
handling, unmapped resources, and sanitized `500` behavior remain defined by the Service Skeleton
design §6. This feature adds only the four Inventory entries and the create-specific conflict
entry in §7.

## 3. Creation orchestration and persistence

### 3.1 Decision order

`PricingService.create` uses this fixed order:

1. Call `PricingRepository.existsById(serialNumber)`.
2. If true, throw `PricingAlreadyExistsException` immediately. Inventory is not called.
3. Call `InventoryGateway.exists(serialNumber)`.
4. If false, throw `InventoryItemNotFoundException`. The database is not mutated.
5. Call `PricingRepository.saveAndFlush(pricing)`.
6. If the database reports `DataIntegrityViolationException`, translate it to
   `PricingAlreadyExistsException`.

The preliminary local lookup avoids an unnecessary network request for ordinary duplicates. It
is an optimization and a stable ordering rule, not the uniqueness authority.

### 3.2 Concurrent uniqueness

PostgreSQL's primary key on `pricing.pricing_entries.serial_number` is the final authority.
Two requests can both observe no row and both receive Inventory confirmation. `saveAndFlush`
forces the insert constraint to be evaluated before the service returns; one insert wins and each
loser receives the same stable `409` contract as a sequential duplicate. No failing request
overwrites or deletes the winning row.

### 3.3 Transaction boundary

`PricingService.create` intentionally has no service-level `@Transactional` annotation. The
local existence check and `saveAndFlush` use Spring Data repository transaction semantics, while
the Inventory network call runs between them without holding an application transaction or
database connection open for remote latency and retry waiting.

The tradeoff is a deliberate check-then-act race, closed by the database constraint and exception
translation in §3.2. A distributed transaction is neither required nor attempted: Inventory is
read-only, and Pricing mutates only after confirmation.

### 3.4 Operation boundary

Inventory is consulted only by create. Retrieval, listing, replacement, and deletion operate on
Pricing-owned state and do not revalidate the serial number. Later deletion of an Inventory item
does not automatically remove existing pricing.

## 4. Inventory HTTP adapter

### 4.1 Client and configuration

`InventoryHttpClientConfig` uses:

```java
@ImportHttpServices(group = "inventory", types = InventoryHttpClient.class)
```

The declarative client defines `GET /api/v1/inventory/{serialNumber}` and returns
`ResponseEntity<Void>` because Pricing needs only the status. The `inventory` HTTP-service group
is configured by these tunable defaults:

| Setting | Default | Purpose |
| --- | --- | --- |
| Base URL | `${INVENTORY_BASE_URL:http://inventory}` | Environment override with Compose/DNS-friendly default |
| Connect timeout | `500ms` | Bound connection establishment |
| Read timeout | `1500ms` | Bound waiting for the response |

The base URL contains no API suffix; the interface contributes `/api/v1/inventory` and the
method contributes `/{serialNumber}`.

### 4.2 Status interpretation

`RestInventoryService.queryInventory` interprets the response as follows:

| Inventory outcome | Adapter result |
| --- | --- |
| Exact `200 OK` | Return `true` |
| `404 Not Found`, including `HttpClientErrorException` | Return `false` |
| Other normal response status, such as `204 No Content` | Throw `UnexpectedInventoryResponseException` |
| Other `4xx` | Propagate `HttpClientErrorException` for later translation |
| `5xx` | Propagate `HttpServerErrorException` through retry/circuit handling |
| I/O, connection, or timeout access failure | Propagate `ResourceAccessException` through retry/circuit handling |

An arbitrary `2xx` is not accepted as existence because that would hide a protocol drift. The
Inventory response body is neither deserialized nor retained.

### 4.3 Adapter-to-domain translation

After the resilience decorators finish, `RestInventoryService.exists` translates transport
details into use-case exceptions:

| Final transport outcome | Domain exception |
| --- | --- |
| Inventory `400` | `InvalidInventoryReferenceException` |
| Other Inventory `4xx` | `InventoryServiceResponseException` |
| `UnexpectedInventoryResponseException` | `InventoryServiceResponseException` |
| `CallNotPermittedException` | `InventoryServiceUnavailableException` |
| `ResourceAccessException` | `InventoryServiceUnavailableException` |
| `HttpServerErrorException` | `InventoryServiceUnavailableException` |

`PricingService` sees only the `InventoryGateway` boolean or domain exceptions. The controller
advice, not the adapter, owns HTTP responses.

## 5. Retry policy

### 5.1 Exception predicate

The `inventory` Retry instance uses
`InventoryRetryableExceptionPredicate implements Predicate<Throwable>` through the
`retry-exception-predicate` property. Resilience4j invokes `test` after a failed supplier
attempt:

| Throwable | Predicate result |
| --- | --- |
| Any `ResourceAccessException` | `true` |
| `HttpServerErrorException` with `502`, `503`, or `504` | `true` |
| Other `HttpServerErrorException` | `false` |
| `HttpClientErrorException`, protocol surprise, or programming exception | `false` |

A class list under `retry-exceptions` could select `ResourceAccessException`, but listing
`HttpServerErrorException` there would retry every `5xx`. The predicate is used because the
policy selects statuses within that exception class. Equivalent alternatives would be
programmatic `RetryConfig.retryOnException` or translating only retryable statuses to a
dedicated exception type; both add configuration/code indirection without improving the current
single adapter.

### 5.2 Tunable defaults

| Setting | Default | Meaning |
| --- | --- | --- |
| `max-attempts` | `2` | One initial attempt plus at most one retry |
| `wait-duration` | `500ms` | Base delay before the retry |
| `enable-randomized-wait` | `true` | Add jitter to avoid synchronized retries |
| `randomized-wait-factor` | `0.2` | Actual delay is between `400ms` and `600ms` |

The numbers are operational defaults and may be tuned using evidence. The stable design decisions
are one bounded retry, retrying only an idempotent Inventory `GET`, the exception/status
classification in §5.1, and randomized delay.

### 5.3 Retry outcomes

A resource-access failure followed by `200` succeeds. Two resource-access failures or two
`502`/`503`/`504` responses end as `503` to the Pricing caller. A `500`, `501`,
or `505` is not retried but still maps to Pricing `503` because it is an Inventory
availability failure. Client errors and unexpected normal statuses are never retried.

## 6. Circuit-breaker policy

### 6.1 Decorator order and logical calls

The adapter constructs:

```java
Supplier<Boolean> retriedCall = Retry.decorateSupplier(retry, inventoryCall);
Supplier<Boolean> guardedCall = CircuitBreaker.decorateSupplier(circuitBreaker, retriedCall);
```

The circuit breaker is outside Retry. It therefore observes the final result of a complete retry
sequence:

- a failed first attempt followed by success is one circuit-breaker success;
- two retryable failed attempts are one circuit-breaker failure;
- a non-retried Inventory `500` is one circuit-breaker failure; and
- an open circuit rejects the logical call before Retry or the HTTP client runs.

Putting Retry outside the circuit breaker would count each physical attempt separately and could
open the circuit from a smaller number of client requests. That is not the selected policy.

### 6.2 Tunable defaults

| Setting | Default |
| --- | --- |
| Sliding-window type | Count based |
| Sliding-window size | `20` logical calls |
| Minimum calls before failure-rate evaluation | `10` |
| Failure-rate threshold | `50%` |
| Open-state wait | `30s` |
| Permitted half-open calls | `3` |

The numeric thresholds are tunable defaults. The application does not enable automatic
open-to-half-open transition, so no monitoring thread changes the state merely because 30 seconds
passes. Once the wait has elapsed, the next attempted call can transition the breaker to
half-open; at most three probe calls then determine whether normal traffic resumes or the breaker
opens again.

The open-state wait is not a retry delay and does not reset recorded calls while the circuit is
closed. It is the minimum time newly requested calls are rejected after the breaker opens. The
half-open probes are the mechanism that tests recovery.

### 6.3 Recorded and ignored outcomes

The circuit records `ResourceAccessException` and every `HttpServerErrorException` as failures,
including `5xx` statuses that are not retried. It ignores `HttpClientErrorException` and
`UnexpectedInventoryResponseException` completely: they do not consume a sliding-window entry
or affect the failure rate. Inventory `404` is converted to the normal return value `false`
inside the guarded supplier, so it is one successful circuit-breaker call even though creation is
later rejected with `422`.

An exact `200` is also a circuit-breaker success. An unrelated programming exception is neither
in the explicit record list nor the ignore list; it propagates to the shared `500` handling and
is not deliberately treated as evidence that Inventory is unhealthy.

## 7. Creation-specific Problem Details

All rows use the shared fields `type`, `title`, `status`, `detail`, `instance`, and
`code` with `application/problem+json`.

| Condition | Status | Type | Code | Title | Detail |
| --- | --- | --- | --- | --- | --- |
| Local or concurrent duplicate | `409` | `urn:rentflow:problem:pricing-already-exists` | `PRICING_ALREADY_EXISTS` | `Pricing already exists` | `Pricing for serial number '{serialNumber}' already exists.` |
| Inventory rejects reference as invalid | `400` | `urn:rentflow:problem:invalid-inventory-reference` | `INVALID_INVENTORY_REFERENCE` | `Invalid inventory reference` | `Inventory rejected serial number '{serialNumber}' as invalid.` |
| Inventory item absent | `422` | `urn:rentflow:problem:inventory-item-not-found` | `INVENTORY_ITEM_NOT_FOUND` | `Inventory item not found` | `Inventory item '{serialNumber}' was not found.` |
| Inventory response violates expected protocol | `502` | `urn:rentflow:problem:inventory-service-error` | `INVENTORY_SERVICE_ERROR` | `Inventory service error` | `Inventory service returned an unexpected response.` |
| Inventory unavailable or circuit open | `503` | `urn:rentflow:problem:inventory-service-unavailable` | `INVENTORY_SERVICE_UNAVAILABLE` | `Inventory service unavailable` | `Inventory service is temporarily unavailable.` |

The `502` and `503` details intentionally omit Inventory response bodies, URLs, exceptions, and
status details. The exception cause remains available server-side without becoming part of the
public contract.

## 8. Configuration and observability

`application.yaml` owns the defaults from §4.1, §5.2, and §6.2 under
`spring.http.serviceclient.inventory` and `resilience4j.*.instances.inventory`.
`INVENTORY_BASE_URL` is the environment-specific override; no Inventory credential is defined.

`resilience4j-spring-boot4` creates `CircuitBreakerRegistry` and `RetryRegistry`.
`RestInventoryService` retrieves both named `inventory` instances rather than using
annotation-driven proxies, making decorator order explicit.

Resilience4j measurements such as `resilience4j.circuitbreaker.calls` and
`resilience4j.retry.calls` are registered in `MeterRegistry` with
`name=inventory`. The shared Actuator HTTP exposure currently includes only `health`, so metric
registration does not imply that `/actuator/metrics` is publicly exposed.

## 9. OpenAPI contract

The generated `createPricing` operation documents:

- the shared `PricingDTO` request and `201` response;
- the `Location` response header with URI format;
- the shared `ProblemResponse` schema for every error; and
- exactly `201`, `400`, `406`, `409`, `415`, `422`, `500`, `502`,
  and `503`.

The Service Skeleton design §7 owns document generation, shared schemas, endpoints, and absence
of security schemes. This section owns only creation-specific operation coverage.

The root README contains a copyable `POST /api/v1/pricing` example using the shared valid
representation. Environment-specific Inventory routing is supplied through
`INVENTORY_BASE_URL` as defined in §4.1.

## 10. Verification design

### 10.1 Unit verification

`PricingServiceTest` uses Mockito without a Spring context to prove local-duplicate short
circuiting, Inventory-before-save ordering, missing Inventory rejection, propagation of
availability failure, successful `saveAndFlush`, and uniqueness-race translation.

`RestInventoryServiceTest` uses a mocked `InventoryHttpClient` and real in-memory
Resilience4j registries. It proves exact `200`/`404` handling, selected retries, non-retried
`5xx` behavior, `4xx` and unexpected-status translation, logical circuit-breaker metrics,
and fail-fast open-circuit behavior. Its retry wait is zero to keep unit tests deterministic.

`InventoryRetryableExceptionPredicateTest` exhaustively checks the selected
`ResourceAccessException` and `502`/`503`/`504` cases plus representative rejected
server, client, and programming exceptions.

### 10.2 Spring and HTTP verification

`PricingIT` uses real PostgreSQL but replaces `InventoryGateway` with a Mockito Spring bean.
It verifies the public `201`/`400`/`409`/`422`/`502`/`503` contracts,
unchanged database state on failure, case-sensitive identity, and concurrent uniqueness.

`OpenApiIT` verifies all create response codes and schemas, loads the exact named retry and
circuit-breaker configuration, checks the randomized retry interval is within `400ms` to
`600ms`, and proves both named components publish measurements to `MeterRegistry`.

The suite intentionally separates adapter policy from full-stack HTTP response mapping. It does
not start a live Inventory service; outbound socket behavior is represented by the mocked
`InventoryHttpClient` in adapter unit tests.

### 10.3 Isolated container acceptance

The shared container smoke script still sends `POST /api/v1/pricing` and expects to retrieve the
created row across application and stack restarts. After Inventory validation was added,
`compose.yaml` still defines only `pricing` and `rentflow-postgres` and does not set
`INVENTORY_BASE_URL`. The default `http://inventory` target is therefore absent from the
isolated Compose project, so the creation fixture currently ends in Pricing `503` and the
smoke workflow cannot reach its persistence assertions.

The completion design is a deterministic Inventory HTTP fixture in the isolated smoke topology,
with Pricing's `INVENTORY_BASE_URL` pointed to it. The fixture must return exact `200` for
`SMOKE-001` and may return `404` for other serials. This keeps the smoke test self-contained,
exercises the real HTTP client and configuration path, and avoids requiring the full Inventory
service or bypassing creation with a direct database insert.

## 11. Edge cases

| Case | Result |
| --- | --- |
| Existing local serial while Inventory is unavailable | `409`; no Inventory call |
| Inventory `404` | Normal adapter result `false`, one successful breaker call, Pricing `422` |
| Inventory `400` | No retry, ignored by breaker, Pricing `400` |
| Inventory `409` or another non-`404` client error | No retry, ignored by breaker, Pricing `502` |
| Inventory `204` or another unexpected normal status | No retry, ignored by breaker, Pricing `502` |
| Inventory `502`, `503`, or `504` once, then `200` | Create succeeds; one breaker success |
| Selected retryable failure twice | Pricing `503`; one breaker failure |
| Inventory `500` | No retry; Pricing `503`; one breaker failure |
| Circuit open | No Retry or HTTP invocation; Pricing `503` |
| Two concurrent creates after Inventory confirms both | One row; losing insert maps to `409` |
| Inventory becomes unavailable after pricing exists | Reads/replacement/deletion remain local |

## 12. Requirements traceability

| Acceptance criteria | Design coverage |
| --- | --- |
| AC1.1-AC1.2 | §2.1-§2.2, §3.1, §3.3 |
| AC1.3-AC1.4 | §3.1-§3.2, §7, §10 |
| AC1.5 | §2.1, §2.3-§2.4, §10.2 |
| AC2.1 | §3.1, §4.1-§4.2 |
| AC2.2-AC2.5 | §2.3, §4.2-§4.3, §7 |
| AC3.1-AC3.2 | §5, §10.1 |
| AC3.3-AC3.4 | §6, §10.1-§10.2 |
| AC3.5 | §1.3, §8, §10.2 |
| AC4.1 | §9, §10.2 |
| AC4.2 | §1.3, §4.1, §5.2, §6.2, §8, §10.2 |
| AC4.3 | §9 |
| AC4.4 | §10.3 |

Every acceptance criterion has a design section and an observable verification path.
