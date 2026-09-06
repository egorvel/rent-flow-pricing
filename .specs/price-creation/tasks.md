# Price Creation Implementation Tasks

Status: T1 implemented; T2 pending.

## 1. Delivery record

The main implementation predates this specification split and was delivered atomically in commit
`4d72330 Improve price creation`. To preserve the project rule that one task corresponds to one
safe commit, this retrospective task map contains one completed implementation task rather than
inventing several commits that did not occur.

Review of the integrated repository found one later verification gap: the isolated Compose smoke
test has no Inventory dependency for its create fixture. T2 records that work as a separate safe
commit. No task may skip tests, formatting, compilation, or migration checks.

## 2. Dependencies

T1 has no feature-task dependency. It builds on the implemented Service Skeleton foundation,
especially the shared DTO/validation, persistence, Problem Details, OpenAPI, and verification
contracts referenced in `design.md` §1.1. T2 depends on T1 because it exercises the implemented
Inventory-aware create flow through containers.

## 3. Implementation tasks

### T1 - Deliver Inventory-validated price creation

**State:** Complete.

**Commit:** `4d72330 Improve price creation`.

**Depends on:** Service Skeleton foundation; no preceding task in this feature.

**Refs.** `requirements.md` AC1.1-AC1.5, AC2.1-AC2.5, AC3.1-AC3.5, and
AC4.1-AC4.3; `design.md` §1-§10.2 and §11-§12.

**Scope.**

- Extend `POST /api/v1/pricing` with local duplicate short-circuiting, an
  `InventoryGateway` existence decision, and database-authoritative uniqueness translation.
- Add the declarative Inventory HTTP client and adapter with exact response classification.
- Add bounded, jittered Retry and a logical-call CircuitBreaker using named `inventory`
  configuration.
- Add creation-specific domain exceptions, RFC 9457 mappings, OpenAPI responses, and Micrometer
  registration.
- Extend no-context unit, PostgreSQL integration, configuration, generated-contract, and
  architecture verification.

**DoD.**

- [x] A valid unauthenticated `POST /api/v1/pricing` whose serial is confirmed by Inventory
  returns `201` with all six exact values and the canonical `Location`, and PostgreSQL retains
  the case-sensitive, client-assigned identity (AC1.1-AC1.2).
- [x] `PricingServiceTest` proves an existing local serial returns conflict before
  `InventoryGateway`, preserves the row, and never saves; `PricingIT` proves the stable
  `409` body (AC1.3).
- [x] The real-PostgreSQL concurrent create test proves one winning row and one stable conflict,
  while service tests prove `DataIntegrityViolationException` from `saveAndFlush` is
  translated rather than leaked (AC1.4).
- [x] The create validation matrix covers missing, null, unknown, malformed, wrong-type,
  fractional-integer, pattern, precision, scale, and numeric-bound failures; each returns `400`
  without persistence, and Spring validation prevents the Inventory port from being reached
  (AC1.5).
- [x] `InventoryHttpClient` targets
  `GET /api/v1/inventory/{serialNumber}` and ignores the body; adapter tests prove only exact
  `200` returns true and `404` returns false (AC2.1-AC2.2).
- [x] Controller integration tests assert the exact `400`
  `INVALID_INVENTORY_REFERENCE`, `422` `INVENTORY_ITEM_NOT_FOUND`, `502`
  `INVENTORY_SERVICE_ERROR`, and `503` `INVENTORY_SERVICE_UNAVAILABLE` Problem
  Details fields and unchanged database state (AC2.2-AC2.5).
- [x] Adapter tests distinguish Inventory `400`, `404`, other `4xx`, unexpected normal
  statuses, every `5xx` family outcome, resource-access failures, and
  `CallNotPermittedException` without leaking transport exceptions to `PricingService`
  (AC2.2-AC2.5).
- [x] `InventoryRetryableExceptionPredicateTest` proves resource-access and only
  `502`/`503`/`504` server exceptions are retryable; `RestInventoryServiceTest`
  proves at most two physical attempts and no retry for other `5xx`, client, protocol, or
  programming outcomes (AC3.1-AC3.2).
- [x] Real Resilience4j registries in adapter tests prove Retry is inside CircuitBreaker, a
  recovered retry is one breaker success, an exhausted retry is one breaker failure, ignored
  outcomes do not enter the window, and an open circuit reaches neither Retry nor Inventory
  (AC3.3-AC3.4).
- [x] The Spring configuration test proves a count window of 20, minimum 10 calls, 50 percent
  threshold, 30-second open wait, three half-open probes, two retry attempts, and a randomized
  `400ms`-`600ms` delay (AC3.1, AC3.4, AC4.2).
- [x] The application context resolves the named Inventory HTTP client, Retry, and CircuitBreaker
  from `application.yaml`, with `INVENTORY_BASE_URL` override support, `500ms` connect
  timeout, and `1500ms` read timeout (AC4.2).
- [x] `OpenApiIT` proves `createPricing` exposes the shared schemas, URI-formatted
  `Location`, and exactly `201`, `400`, `406`, `409`, `415`, `422`, `500`,
  `502`, and `503` (AC4.1).
- [x] The root README contains a copyable `POST /api/v1/pricing` example with all six valid
  shared fields (AC4.3).
- [x] `OpenApiIT` exercises both named resilience components and finds
  `resilience4j.circuitbreaker.calls` and `resilience4j.retry.calls` with
  `name=inventory` in `MeterRegistry` without a direct `resilience4j-micrometer`
  dependency (AC3.5).
- [x] `mvn -B -ntp clean verify` succeeds with formatting, unit, PostgreSQL integration,
  generated OpenAPI, configuration, and architecture checks active and no skip flags.

### T2 - Restore isolated container creation acceptance

**State:** Pending.

**Commit:** `test: restore inventory-aware container smoke`.

**Depends on:** T1.

**Refs.** `requirements.md` AC4.4; `design.md` §4.1 and §10.3;
[Service Skeleton requirements](../service-skeleton/requirements.md) AC9.3 and AC9.6;
[Service Skeleton design](../service-skeleton/design.md) §9 and §11.4.

**Scope.**

- Add a deterministic Inventory HTTP fixture to the isolated smoke topology without introducing a
  dependency on a separately running RentFlow service.
- Configure the Pricing container's `INVENTORY_BASE_URL` to target that fixture.
- Keep the existing create, retrieve, restart, database-outage, recovery, and stack-recreation
  assertions intact.

**DoD.**

- [ ] `docker compose config` for the isolated smoke project contains a reachable Inventory
  fixture and passes its URL to Pricing through `INVENTORY_BASE_URL`.
- [ ] The fixture returns exact `200 OK` for `SMOKE-001`, allowing the real declarative
  `InventoryHttpClient` path to authorize creation without a direct database insert.
- [ ] `scripts/container-smoke-test.sh` creates `SMOKE-001`, retrieves its exact six values,
  and retrieves the same row after application restart and stack recreation without deleting the
  PostgreSQL volume.
- [ ] The smoke test still proves PostgreSQL-outage readiness `503` with liveness `200`,
  bounded recovery, restricted schema bootstrap, and unprivileged runtime behavior.
- [ ] Shell syntax checks and `mvn -B -ntp clean verify` succeed with no skip flags before the
  isolated smoke test is accepted.

## 4. Acceptance-criteria traceability

| Acceptance criteria | Regression-sensitive T1 evidence |
| --- | --- |
| AC1.1-AC1.2 | Created representation, `Location`, exact values, persistence, and identity assertions |
| AC1.3-AC1.4 | Duplicate ordering, no-Inventory interaction, concurrent race, and unchanged-row assertions |
| AC1.5 | Invalid-body matrix plus database and collaborator non-interaction assertions |
| AC2.1 | Exact Inventory method/path and `200` adapter result assertions |
| AC2.2-AC2.5 | Adapter classification plus exact public Problem Details and no-persistence assertions |
| AC3.1-AC3.2 | Predicate status matrix and physical invocation-count assertions |
| AC3.3-AC3.4 | Breaker metric counts, decorator order, open rejection, and no-client-call assertions |
| AC3.5 | Named Retry/CircuitBreaker meter lookup assertions |
| AC4.1 | Generated `createPricing` operation, status, header, and schema assertions |
| AC4.2 | Application-context configuration and named-registry assertions |
| AC4.3 | README create-command and valid-payload inspection |
| AC4.4 | T2 self-contained Inventory fixture plus create/restart persistence assertions |

Every acceptance criterion has a regression-sensitive DoD. T1 covers the implemented application
behavior; T2 remains visibly unchecked until isolated container acceptance is restored.
