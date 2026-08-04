# Pricing Service Skeleton Requirements

Status: Requirements, design, and implementation tasks defined; ready for implementation.

## Context

RentFlow needs a pricing service that owns the pricing terms assigned to equipment. The repository
currently has no application skeleton, so this increment establishes the smallest useful pricing
CRUD API together with the engineering baseline required to build, run, inspect, and verify it.
Actual rental-price calculation is intentionally deferred to a later feature.

The initial pricing resource has exactly these business attributes:

- `serialNumber`: a unique, manually assigned, immutable equipment identifier.
- `price`: the positive, currency-agnostic daily price with at most two fractional digits.
- `weekendRate`: a positive multiplier applied to the daily price on weekend rental days, with at
  most four fractional digits.
- `longRentalCondition`: the positive whole-number rental duration in calendar days at which the
  long-rental discount becomes applicable.
- `longRentalDiscount`: the fractional discount in `[0, 1)`, with at most four fractional digits.
- `deposit`: a non-negative, currency-agnostic security deposit with at most two fractional digits.

The service exposes an unauthenticated JSON REST API under `/api/v1/pricing`:

| Capability | Method and resource |
| --- | --- |
| Create pricing | `POST /api/v1/pricing` |
| List pricing | `GET /api/v1/pricing` |
| Retrieve pricing | `GET /api/v1/pricing/{serialNumber}` |
| Fully replace pricing | `PUT /api/v1/pricing/{serialNumber}` |
| Permanently delete pricing | `DELETE /api/v1/pricing/{serialNumber}` |

This feature also establishes executable API documentation, a database migration path, container
packaging, local developer documentation, health reporting, and automated verification. The
mandated platform is Java 25, Spring Boot 4, Maven, PostgreSQL 18.4, Flyway, Spring Data JPA,
Hibernate, and Testcontainers.

RentFlow microservices currently share the PostgreSQL database `rentflow`. Pricing connects with
its own `pricing` login role and owns only the `pricing` schema and its objects. Deployed database,
role, and schema provisioning are platform responsibilities; the local Compose stack bootstraps
equivalent development prerequisites. Pricing migrations and Flyway history remain confined to
the Pricing-owned schema.

## User stories

### US1 - Create pricing

As a pricing operator, I want to register pricing terms for an equipment serial number so that
RentFlow can retain the terms used by later rental workflows.

- **AC1.1 (Event-driven):** When a client submits a valid complete pricing representation, the
  pricing service shall persist it and return `201 Created` with the created representation and a
  `Location` header for that resource.
- **AC1.2 (Ubiquitous):** The pricing service shall use the client-supplied `serialNumber` and
  shall not generate or replace it.
- **AC1.3 (Unwanted):** If a client submits a `serialNumber` that already identifies pricing, then
  the pricing service shall return `409 Conflict` and shall leave the existing pricing unchanged.
- **AC1.4 (Unwanted):** If a create request is malformed, incomplete, contains an unknown property,
  or violates a pricing-field constraint, then the pricing service shall return `400 Bad Request`
  and shall not persist pricing.

### US2 - Retrieve pricing

As a pricing consumer, I want to retrieve pricing by serial number so that I can use the equipment's
current pricing terms.

- **AC2.1 (Event-driven):** When a client requests an existing `serialNumber`, the pricing service
  shall return `200 OK` with all six business attributes.
- **AC2.2 (Unwanted):** If a requested `serialNumber` does not identify pricing, then the pricing
  service shall return `404 Not Found`.

### US3 - Browse pricing

As a pricing consumer, I want a bounded and sortable pricing collection so that I can browse it
without retrieving the complete data set.

- **AC3.1 (Event-driven):** When a client requests the collection without query parameters, the
  pricing service shall return `200 OK` with page zero of size 20, deterministic `serialNumber`
  ordering, a non-null `content` array, and nested `page` metadata.
- **AC3.2 (Event-driven):** When a client supplies valid `page` and `size` parameters, the pricing
  service shall return the requested page with `size` limited to 100.
- **AC3.3 (Event-driven):** When a client requests ascending or descending sorting by any pricing
  field, the pricing service shall return a deterministic page using `serialNumber ASC` as the
  tie-breaker for non-serial sorts.
- **AC3.4 (Unwanted):** If pagination or sorting parameters are invalid, repeated, unknown, or
  unsupported, then the pricing service shall return `400 Bad Request`.
- **AC3.5 (Event-driven):** When a valid collection request has no results, the pricing service
  shall return `200 OK` with an empty `content` array and accurate nested `page` metadata.

### US4 - Replace pricing

As a pricing operator, I want to replace an equipment item's pricing terms so that all retained
terms represent the current commercial policy.

- **AC4.1 (Event-driven):** When a client submits a valid full replacement for existing pricing,
  the pricing service shall replace every mutable pricing field and return `200 OK` with the
  updated representation.
- **AC4.2 (Ubiquitous):** The pricing service shall keep `serialNumber` immutable after creation.
- **AC4.3 (Unwanted):** If a replacement body contains a `serialNumber` different from the path
  identifier, then the pricing service shall return `400 Bad Request` and shall leave pricing
  unchanged.
- **AC4.4 (Unwanted):** If a replacement is malformed, incomplete, contains an unknown property,
  or violates a pricing-field constraint, then the pricing service shall return `400 Bad Request`
  and shall leave pricing unchanged.
- **AC4.5 (Unwanted):** If a client attempts to replace missing pricing, then the pricing service
  shall return `404 Not Found` and shall not create pricing.

### US5 - Delete pricing

As a pricing operator, I want to remove obsolete or incorrectly assigned pricing so that it is no
longer available to consumers.

- **AC5.1 (Event-driven):** When a client deletes existing pricing, the pricing service shall
  permanently remove it and return `204 No Content` with an empty response body.
- **AC5.2 (Event-driven):** When successfully deleted pricing is subsequently retrieved, the
  pricing service shall return `404 Not Found`.
- **AC5.3 (Unwanted):** If a client attempts to delete missing pricing, then the pricing service
  shall return `404 Not Found`.

### US6 - Receive consistent API failures

As an API consumer, I want predictable error responses so that my integration can distinguish and
diagnose rejected requests.

- **AC6.1 (Unwanted):** If a REST operation returns a client or server error, then the pricing
  service shall return an RFC 9457 Problem Details response using `application/problem+json` and
  the documented fields.
- **AC6.2 (Unwanted):** If request validation fails, then the pricing service shall include
  deterministic machine-readable field violations in the Problem Details response.
- **AC6.3 (Unwanted):** If a request contains malformed JSON, then the pricing service shall return
  `400 Bad Request` without exposing an internal exception or stack trace.
- **AC6.4 (Unwanted):** If a write request uses an unsupported media type, then the pricing service
  shall return `415 Unsupported Media Type`.
- **AC6.5 (Unwanted):** If a client invokes an unsupported HTTP method on a pricing resource,
  including `PATCH`, then the pricing service shall return `405 Method Not Allowed`.
- **AC6.6 (Unwanted):** If an unexpected server failure occurs, then the pricing service shall
  return `500 Internal Server Error` without exposing secrets, database details, exceptions, or
  stack traces.

### US7 - Inspect and exercise the API contract

As an API consumer, I want executable API documentation so that I can discover and try the service
without reading its implementation.

- **AC7.1 (State-driven):** While the service is running, the pricing service shall expose a
  machine-readable OpenAPI document and an interactive Swagger UI at documented locations.
- **AC7.2 (Ubiquitous):** The OpenAPI contract shall describe every in-scope operation, request and
  response schema, query parameter, validation rule, success response, and documented error.
- **AC7.3 (Ubiquitous):** The OpenAPI contract shall provide representative pricing and Problem
  Details examples that conform to their schemas.

### US8 - Initialize and preserve the database schema

As a service operator, I want deterministic database migrations so that every environment starts
with a compatible, versioned schema.

- **AC8.1 (Event-driven):** When the service starts against PostgreSQL 18.4 with a pre-provisioned
  empty `pricing` schema in `rentflow`, the pricing service shall apply Flyway migrations that
  create the required Pricing-owned objects before accepting traffic.
- **AC8.2 (Event-driven):** When the service starts with pending valid migrations, the pricing
  service shall apply each pending migration once and shall preserve existing pricing data.
- **AC8.3 (Unwanted):** If database connectivity, the required role or schema, migration, or JPA
  schema validation fails during startup, then the pricing service shall not report itself ready.
- **AC8.4 (Event-driven):** When the application restarts while its database remains intact, the
  pricing service shall make previously committed pricing available.
- **AC8.5 (Ubiquitous):** The pricing service shall use Flyway as the sole mechanism for creating
  and evolving Pricing-owned tables, indexes, and constraints after prerequisites are provisioned.
- **AC8.6 (Ubiquitous):** The pricing service shall store Flyway history and application objects
  only in the `pricing` schema and shall not modify another RentFlow service's objects.

### US9 - Run and observe the service in containers

As a developer or operator, I want a production-style image and local stack so that I can run the
service consistently.

- **AC9.1 (Event-driven):** When the application image is built from a clean checkout, the
  container build shall compile the application in a Java 25 build stage and produce a separate
  Java 25 runtime image.
- **AC9.2 (State-driven):** While the application container is running, the operating-system
  process shall run as an unprivileged user.
- **AC9.3 (Event-driven):** When the documented Compose command is run with Docker available, the
  stack shall start the pricing service and PostgreSQL 18.4 database `rentflow` without manually
  provisioned dependencies.
- **AC9.4 (State-driven):** While the application and its dependencies are healthy, the pricing
  service shall report `UP` from its documented liveness and readiness endpoints.
- **AC9.5 (Unwanted):** If PostgreSQL becomes unavailable after startup, then the pricing service
  shall report non-ready while keeping liveness independent of database availability.
- **AC9.6 (Event-driven):** When the application or Compose stack restarts without deleting the
  database volume, the pricing service shall retain previously committed pricing.
- **AC9.7 (Ubiquitous):** The containerized service shall obtain environment-specific database and
  server configuration from environment variables rather than baked-in production credentials.

### US10 - Onboard a contributor

As a contributor, I want accurate setup and usage documentation so that I can build, run, inspect,
and test the service from a clean checkout.

- **AC10.1 (Ubiquitous):** The repository README shall document prerequisites, supported platform
  versions, local and Compose startup, shutdown, configuration, migrations, health, Swagger UI,
  and OpenAPI.
- **AC10.2 (Ubiquitous):** The repository README shall document the single Maven verification
  command and shall explain the checks it runs.
- **AC10.3 (Ubiquitous):** The repository README shall contain executable examples for create,
  retrieve, paginated/sorted list, replace, and delete operations.
- **AC10.4 (Ubiquitous):** The repository shall include Maven Wrapper so contributors can run the
  lifecycle without installing Maven.

### US11 - Verify the service automatically

As a maintainer, I want one repeatable verification lifecycle so that regressions are detected
before changes are integrated.

- **AC11.1 (Event-driven):** When `mvn -B -ntp clean verify` runs in an environment satisfying the
  documented prerequisites, the build shall compile the service and execute formatting, unit,
  integration, OpenAPI, and architecture checks.
- **AC11.2 (Ubiquitous):** The pricing service's unit tests shall run without a Spring context and
  shall use Mockito where collaboration behavior requires test doubles.
- **AC11.3 (Ubiquitous):** The pricing service's persistence and full-stack integration tests
  shall use Testcontainers with real PostgreSQL 18.4 and shall require no manually provisioned
  test database.
- **AC11.4 (Ubiquitous):** The pricing service's integration tests shall verify migrations, CRUD,
  pagination, sorting, validation, conflict, and not-found behavior through externally observable
  results.
- **AC11.5 (Ubiquitous):** The pricing service's architecture tests shall enforce the prescribed
  configuration, controller, converter, DTO, service, repository, entity, and utility boundaries.
- **AC11.6 (Ubiquitous):** The pricing service's automated tests shall verify that the generated
  OpenAPI contract contains every in-scope operation and remains consistent with runtime paths.

### US12 - Use the initial service without credentials

As a trusted internal consumer, I want to call the initial pricing API without credentials so that
authentication does not block validation of the service skeleton.

- **AC12.1 (Event-driven):** When a client invokes an in-scope pricing operation without
  authentication credentials, the pricing service shall process it according to its functional
  rules.
- **AC12.2 (Ubiquitous):** The pricing service shall not expose registration, login, token, user,
  role, or permission-management capabilities in this feature.

## Out of scope

- Rental-price calculation, weekend-date classification, rental-period input, proration, taxes,
  fees, currency conversion, and monetary rounding during calculations.
- Currency codes; `price` and `deposit` are currency-agnostic values in this increment.
- Authentication, authorization, users, roles, permissions, and tenant isolation.
- Partial updates with `PATCH`, soft deletion, audit history, timestamps, optimistic locking,
  ETags, conditional writes, bulk operations, and imports or exports.
- Generated serial numbers or changes to a serial number after creation.
- Collection filtering and full-text, fuzzy, or range search.
- Cross-service queries, foreign keys, and migrations against objects owned by other services.
- Production orchestration, cloud infrastructure, CI/CD, database backups, replication, and high
  availability.
- A user interface other than Swagger UI and compatibility guarantees beyond `/api/v1`.

## Resolved questions

1. **Initial capability:** This increment is CRUD-only; price calculation is deferred. See
   `design.md` §2.4 and §12.
2. **Money representation:** `price` and `deposit` are currency-agnostic decimals stored as
   `numeric(19,2)`. See `design.md` §4.1 and §5.1.
3. **Money bounds:** `price` is greater than zero and `deposit` is non-negative. See `design.md`
   §5.1-§5.2.
4. **Weekend rate:** `weekendRate` is a positive multiplier with at most four fractional digits.
   See `design.md` §4.1 and §5.2.
5. **Long-rental threshold:** `longRentalCondition` is a positive integer number of calendar days,
   and a later calculation applies the discount at durations greater than or equal to it. See
   `design.md` §5.2 and §12.
6. **Long-rental discount:** `longRentalDiscount` is a fraction in `[0, 1)` with at most four
   fractional digits. See `design.md` §4.1 and §5.2.
7. **Collection retrieval:** Listing uses bounded pagination and allowlisted sorting by all six
   fields; filtering is deferred. See `design.md` §2.3 and §3.3.
8. **Identity:** The case-sensitive client-assigned serial number is the immutable public and
   persistence identifier. See `design.md` §4.1 and §5.2.
9. **Updates and deletion:** `PUT` is full update-only replacement and `DELETE` permanently removes
   pricing; `PATCH` and soft deletion are absent. See `design.md` §3.4-§3.6.
10. **Access:** The initial API, documentation, and health endpoints are unauthenticated. See
    `design.md` §2.2.
11. **Database ownership:** Pricing uses the shared `rentflow` database through the `pricing` role
    and owns only the `pricing` schema and schema-local Flyway history. See `design.md` §4.1-§4.2
    and §8.1.
12. **Local database:** Compose provisions equivalent role/schema prerequisites for standalone
    development; deployed infrastructure provisions them externally. See `design.md` §9.2.
13. **Container delivery:** The repository includes a multi-stage Java 25 image and Compose stack
    with services `pricing` and `rentflow-postgres`. See `design.md` §9.
14. **Increment strategy:** Implementation proceeds as verified, dependency-ordered commits in
    `tasks.md` §2-§3, although this scaffold may be delivered as one working-tree change.
