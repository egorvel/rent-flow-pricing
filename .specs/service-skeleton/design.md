# Pricing Service Skeleton Design

Status: Implemented; Price Creation tasks T2 tracks the container-smoke integration gap.

This document implements `requirements.md`. Numbered sections are stable traceability targets.
The [Price Creation design](../price-creation/design.md) owns the `POST` workflow, Inventory
integration, resilience, and creation-specific outcomes.

## 1. Architecture

### 1.1 Runtime and platform baseline

The service is one Maven module and one deployable Spring Boot process. A layered monolith is
appropriate because the service owns one aggregate. Feature-specific outbound integrations use
ports and adapters without changing the shared layer model; Price Creation currently adds the
Inventory integration described in its design §1 and §4.

| Concern | Decision |
| --- | --- |
| Coordinates | `com.rentflow:rent-flow-pricing:0.0.1-SNAPSHOT` |
| Java | Java 25 source, target, build, and runtime |
| Build | Maven Wrapper 3.3.4 running Maven 3.9.16 |
| Framework | Spring Boot 4.1.0 and its dependency management |
| HTTP | Synchronous Spring MVC with embedded Tomcat |
| JSON | Boot-managed Jackson 3 |
| Persistence | Spring Data JPA, Hibernate, and blocking JDBC |
| Database | PostgreSQL 18.4; shared database `rentflow` |
| Schema management | Flyway SQL migrations |
| API documentation | Springdoc OpenAPI 3.0.3 with Swagger UI |
| Integration testing | Testcontainers 2.0.5, managed by Spring Boot |
| Architecture testing | ArchUnit 1.4.2 |
| Formatting | Spotless Maven Plugin 3.8.0 with palantir-java-format 2.96.0 |

These versions match the verified Inventory-service baseline. Spring Boot manages supported
transitive dependency versions except where a feature deliberately imports its own compatible
BOM. The Resilience4j version and dependency rationale are owned by the Price Creation design
§1.3.

Spring MVC is used rather than WebFlux because JPA and JDBC are blocking. Spring Data REST is not
used because the API requires explicit DTOs, stable errors, strict sorting, and deliberate
transactions. Transport types are Java records; the JPA entity is an ordinary class. The service
does not add Lombok, MapStruct, a service interface with one implementation, generic base
controllers, or generic CRUD abstractions.

### 1.2 Components and packages

| Package | Primary types | Responsibility |
| --- | --- | --- |
| `com.rentflow` | `PricingApplication` | Process entry point |
| `com.rentflow.config` | `OpenApiConfig` | API-document metadata |
| `com.rentflow.controller` | `PricingController`, `ApiExceptionHandler` | HTTP binding and errors |
| `com.rentflow.converter` | `PricingConverter` | Entity-to-DTO conversion |
| `com.rentflow.dto` | `PricingDTO`, problem and violation records | Wire contract |
| `com.rentflow.service` | `PricingService`, `PricingSortField`, exceptions | Use cases and transactions |
| `com.rentflow.repository` | `PricingRepository` | Persistence access |
| `com.rentflow.model` | `Pricing` | Persisted domain state |
| `com.rentflow.util` | Initially empty | Shared helpers only after a demonstrated need |

Creation-specific types in `config`, `service`, and `service.rest` are listed once in the
Price Creation design §1.2. The shared local request flow used by retrieval, browsing,
replacement, and deletion is:

```text
HTTP request -> PricingController -> PricingService -> PricingRepository -> PostgreSQL
                                              -> PricingConverter -> response DTO
```

JPA entities are never serialized directly. The controller validates transport values; the
service owns local existence checks, mutations, and transaction boundaries; the repository owns
database access; the converter produces immutable response records. The create flow and its
Inventory port are defined in the Price Creation design §1.2.

### 1.3 Dependency boundaries

ArchUnit enforces these application-layer dependencies:

| Source layer | May depend on |
| --- | --- |
| `controller` | `converter`, `dto`, `service` |
| `converter` | `dto`, `model` |
| `dto` | No other application layer |
| `service` | `model`, `repository` |
| `repository` | `model` |
| `model` | No other application layer |
| `config` | Framework APIs, `dto`, and `service` |
| `util` | No other application layer |

Lower layers do not depend on controllers or DTOs. Controllers do not call repositories. Package
cycles are forbidden. Controllers, converters, services, and repositories use their corresponding
suffixes, and only controllers own Spring MVC mapping annotations.

### 1.4 Transaction boundaries

`PricingService` is a concrete Spring `@Service`. Reads use `@Transactional(readOnly = true)`;
replace and delete use `@Transactional`. Delete loads before removing so missing pricing
produces `404`. Create intentionally uses a different boundary so no database transaction is
held across its Inventory call; Price Creation design §3.3 is authoritative for that decision.

Open Session in View is disabled. The entity has eager scalar fields only and is converted inside
the controller after the transactional service returns.

## 2. HTTP foundations

### 2.1 Paths, media types, and versioning

Business operations are rooted at `/api/v1/pricing`. Paths and serial numbers are case-sensitive.
Successful representations use `application/json`; `POST` and `PUT` require it. Errors use
`application/problem+json`. Successful `DELETE` has no body or response content type.

Jackson rejects unknown JSON properties. Property names are lower camel case, and database-only
details never appear in responses. API version `v1` is path based; header version negotiation is
absent.

### 2.2 Unauthenticated boundary

The service contains no Spring Security dependency or authentication, token, session, user, role,
or permission component. Pricing, Swagger UI, OpenAPI, and health endpoints are callable without
credentials. CORS retains Spring's disabled-by-default behavior.

### 2.3 Resource representations

`PricingDTO` is the complete write and response representation:

```json
{
  "serialNumber": "DRILL-001",
  "price": 125.50,
  "weekendRate": 1.2500,
  "longRentalCondition": 7,
  "longRentalDiscount": 0.1000,
  "deposit": 300.00
}
```

There is no internal identifier, currency, or timestamp. JSON numbers map to `BigDecimal` for the
four decimal-valued fields and `Integer` for `longRentalCondition`; floating-point types are not
used for business values.

The collection controller maps `Page<Pricing>` to `Page<PricingDTO>` and explicitly constructs
`org.springframework.data.web.PagedModel<PricingDTO>`. It does not expose `PageImpl`, enable global
page serialization, or add HATEOAS links:

```json
{
  "content": [
    {
      "serialNumber": "DRILL-001",
      "price": 125.50,
      "weekendRate": 1.2500,
      "longRentalCondition": 7,
      "longRentalDiscount": 0.1000,
      "deposit": 300.00
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`content` is never null, and page totals describe the complete result before page slicing.

### 2.4 CRUD-only capability boundary

No endpoint accepts rental dates or duration, and no endpoint calculates a rental total. The
stored weekend multiplier and long-rental threshold/discount are configuration data only in this
increment. A later calculation feature must specify weekend classification, application order,
rounding, currency, and deposit inclusion before adding calculation behavior.

## 3. REST API contract

### 3.1 Create pricing

`POST /api/v1/pricing` is implemented, but its operation contract is owned by
[Price Creation requirements](../price-creation/requirements.md) and its technical flow is owned
by [Price Creation design](../price-creation/design.md). This skeleton supplies only the shared
`PricingDTO`, validation, persistence, generic error envelope, and OpenAPI foundations used by
that feature.

### 3.2 Retrieve pricing

`GET /api/v1/pricing/{serialNumber}` returns `200` with `PricingDTO`, `400` for invalid serial
syntax, or `404` when pricing is absent. Lookup uses the case-sensitive natural key as supplied.

### 3.3 List and sort pricing

`GET /api/v1/pricing` accepts only:

| Parameter | Type | Default | Rules |
| --- | --- | --- | --- |
| `page` | Integer | `0` | Minimum `0` |
| `size` | Integer | `20` | From `1` through `100` |
| `sort` | String enum | `serialNumber` | Any of the six JSON field names |
| `direction` | String enum | `asc` | `asc` or `desc`, case-insensitive |

The six sort values are `serialNumber`, `price`, `weekendRate`, `longRentalCondition`,
`longRentalDiscount`, and `deposit`. A `PricingSortField` allowlist maps them to JPA attributes;
arbitrary client strings never reach Spring Data property resolution. Every non-serial sort adds
`serialNumber ASC` as a deterministic tie-breaker. Serial sorting uses only its requested
direction because the key is unique.

The controller rejects unknown parameter names, repeated parameters, numeric conversion failures,
out-of-range pagination, blank values, unsupported sorts, and unsupported directions with `400`.
A page beyond the last page is valid and returns an empty `content` array, requested page number
and size, and accurate totals. No filtering is implemented.

### 3.4 Replace pricing

`PUT /api/v1/pricing/{serialNumber}` accepts a complete `PricingDTO`. The body serial is required
and must equal the path with case-sensitive comparison. Valid replacement returns `200` with the
updated DTO. An invalid body or mismatch returns `400`; a missing resource returns `404`; an
unsupported media type returns `415`.

The operation is update-only and never upserts. After loading the entity, the service calls a
domain method that replaces `price`, `weekendRate`, `longRentalCondition`,
`longRentalDiscount`, and `deposit`; no serial-number setter exists.

### 3.5 Delete pricing

`DELETE /api/v1/pricing/{serialNumber}` permanently removes the row. Existing pricing returns
`204 No Content`; invalid serial syntax returns `400`; missing pricing returns `404`. No tombstone,
audit row, or recovery record is written.

### 3.6 Unsupported methods

There is no `PATCH` mapping. Unsupported methods are translated to `405 Method Not Allowed` with
RFC 9457 content while Spring MVC retains its standards-based `Allow` header.

## 4. Persistence design

### 4.1 Entity and relational schema

`Pricing` maps explicitly to `pricing.pricing_entries`. The serial number is both the JPA ID and
database primary key; no hidden generated identifier is added.

| Java field | Column | SQL type | Constraints |
| --- | --- | --- | --- |
| `serialNumber` | `serial_number` | `varchar(64)` | Primary key, immutable, serial pattern |
| `price` | `price` | `numeric(19,2)` | Not null, greater than zero |
| `weekendRate` | `weekend_rate` | `numeric(19,4)` | Not null, greater than zero |
| `longRentalCondition` | `long_rental_condition` | `integer` | Not null, at least one |
| `longRentalDiscount` | `long_rental_discount` | `numeric(5,4)` | Not null, at least zero and less than one |
| `deposit` | `deposit` | `numeric(19,2)` | Not null, non-negative |

The entity has a protected JPA constructor, a complete creation constructor, getters, and
`replaceDetails`. Equality and hash code use the assigned immutable serial number.

The first append-only migration is
`src/main/resources/db/migration/V1__create_pricing_entries.sql`:

```sql
CREATE TABLE pricing.pricing_entries (
    serial_number varchar(64) PRIMARY KEY,
    price numeric(19, 2) NOT NULL,
    weekend_rate numeric(19, 4) NOT NULL,
    long_rental_condition integer NOT NULL,
    long_rental_discount numeric(5, 4) NOT NULL,
    deposit numeric(19, 2) NOT NULL,
    CONSTRAINT chk_pricing_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_pricing_price CHECK (price > 0),
    CONSTRAINT chk_pricing_weekend_rate CHECK (weekend_rate > 0),
    CONSTRAINT chk_pricing_long_rental_condition CHECK (long_rental_condition >= 1),
    CONSTRAINT chk_pricing_long_rental_discount
        CHECK (long_rental_discount >= 0 AND long_rental_discount < 1),
    CONSTRAINT chk_pricing_deposit CHECK (deposit >= 0)
);
```

The primary key supports item lookup and default ordering. No secondary index is added before a
filter or measured query pattern justifies its write and storage cost. The migration does not
create the database, login, schema, extension, seed data, or objects outside `pricing`.

### 4.2 Migration and JPA startup

Flyway uses the primary datasource, default `classpath:db/migration` location, and `pricing` as
both default and managed schema. `create-schemas` is false, so the default history table is
`pricing.flyway_schema_history`. Hibernate uses `ddl-auto: validate` and
`hibernate.default_schema: pricing`; SQL initialization and Open Session in View are disabled.

Production infrastructure provisions database `rentflow`, login role `pricing`, and the
role-owned `pricing` schema. Local Compose and Testcontainers bootstrap only those prerequisites.
A connectivity, missing-schema, privilege, migration, checksum, or Hibernate validation failure
aborts startup. No `schema.sql`, `data.sql`, or `import.sql` is present.

### 4.3 Repository and sorting

`PricingRepository` extends `JpaRepository<Pricing, String>`. Filtering is absent, so
`JpaSpecificationExecutor` and a specifications utility are not introduced.

`PricingSortField` is a service enum containing one allowlisted entity property per public sort
name. Its case-sensitive parser rejects unknown values before a `PageRequest` is built. The
controller maps the returned `Page<Pricing>` to DTOs and wraps it in Spring Data `PagedModel`.

### 4.4 Persistence and concurrency behavior

PostgreSQL is the final authority for serial uniqueness. All failed writes roll back. Replacement
mutates only an entity loaded in the same transaction; deletion loads and removes in one
transaction. There is no `@Version`, ETag, or conditional update. Committed rows survive process
restarts because no lifecycle hook clears the schema. Price Creation design §3.2 owns the
operation-specific uniqueness race and conflict translation.

## 5. Validation and invariants

### 5.1 Transport and query validation

Annotations use explicit messages, and OpenAPI repeats the same constraints.

| Input | Java validation |
| --- | --- |
| Body/path `serialNumber` | Required; `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| `price` | Required `BigDecimal`; `@Digits(integer=17, fraction=2)`; greater than zero |
| `weekendRate` | Required `BigDecimal`; `@Digits(integer=15, fraction=4)`; greater than zero |
| `longRentalCondition` | Required `Integer`; at least `1` |
| `longRentalDiscount` | Required `BigDecimal`; at most four fractional digits; `0 <= value < 1` |
| `deposit` | Required `BigDecimal`; `@Digits(integer=17, fraction=2)`; non-negative |
| Query `page` | Integer at least `0` |
| Query `size` | Integer from `1` through `100` |
| Query `sort` | One of the six exact public field names |
| Query `direction` | `asc` or `desc`, parsed case-insensitively |

The DTO does not round or silently clamp values. Values exceeding declared precision, scale, or
bounds are rejected before service mutation. `PricingController` uses `@Validated`; bodies use
`@Valid`; path and query parameters have explicit constraints. The query-name allowlist and
path/body identity equality are explicit controller checks.

### 5.2 Business invariants

1. Exactly one row may have a case-sensitive serial number.
2. The serial number never changes after creation.
3. Daily price and weekend multiplier are strictly positive.
4. Deposit is non-negative.
5. Long-rental condition is a positive whole number of calendar days.
6. Long-rental discount is a fraction greater than or equal to zero and less than one.
7. A later calculation qualifies when duration is greater than or equal to the condition.
8. `PUT` never creates missing pricing, and `DELETE` is permanent.

The entity constructor and replacement method require non-null values; Bean Validation and SQL
constraints enforce numeric shape and bounds at their respective boundaries.

### 5.3 Validation ordering and atomicity

JSON parsing and Bean Validation happen before service mutation. `PUT` path/body equality is
checked before loading the entity. Rejected writes leave database state unchanged. A JSON token
that cannot be converted to a declared field type is reported as a field validation violation
when the field is identifiable; unreadable JSON syntax uses the separate malformed-JSON problem.
Price Creation design §2.1 and §3.1 own creation-specific validation and decision ordering.

## 6. Error contract

### 6.1 RFC 9457 representation

`ProblemResponse` contains standard Problem Details fields plus stable RentFlow extensions:

```json
{
  "type": "urn:rentflow:problem:validation-failed",
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more request values are invalid.",
  "instance": "/api/v1/pricing",
  "code": "VALIDATION_FAILED",
  "violations": [
    { "field": "price", "message": "must be greater than 0" }
  ]
}
```

`type`, `title`, `status`, `detail`, `instance`, and `code` are always present. `violations` is
present only for validation failures, is non-empty, and is sorted by field then message.
`instance` contains only the request path. Every error uses `application/problem+json`.

### 6.2 Problem catalogue

| Condition | Status | Type | Code | Title |
| --- | --- | --- | --- | --- |
| Validation | `400` | `urn:rentflow:problem:validation-failed` | `VALIDATION_FAILED` | `Request validation failed` |
| Malformed JSON | `400` | `urn:rentflow:problem:malformed-json` | `MALFORMED_JSON` | `Malformed JSON` |
| Pricing absent | `404` | `urn:rentflow:problem:pricing-not-found` | `PRICING_NOT_FOUND` | `Pricing not found` |
| Method unsupported | `405` | `urn:rentflow:problem:method-not-allowed` | `METHOD_NOT_ALLOWED` | `Method not allowed` |
| Response media unavailable | `406` | `urn:rentflow:problem:not-acceptable` | `NOT_ACCEPTABLE` | `Not acceptable` |
| Request media unsupported | `415` | `urn:rentflow:problem:unsupported-media-type` | `UNSUPPORTED_MEDIA_TYPE` | `Unsupported media type` |
| Unmapped path | `404` | `urn:rentflow:problem:resource-not-found` | `RESOURCE_NOT_FOUND` | `Resource not found` |
| Other MVC client error | Original `4xx` | `urn:rentflow:problem:http-error` | `HTTP_ERROR` | `Request failed` |
| Unexpected failure | `500` | `urn:rentflow:problem:internal-error` | `INTERNAL_ERROR` | `Internal server error` |

Not-found detail is `Pricing for serial number '{serialNumber}' was not found.` Internal-error
detail is always `An unexpected error occurred.` Creation-specific conflict and Inventory
problems are defined in Price Creation design §7.

### 6.3 Exception mapping and sanitization

`ApiExceptionHandler` extends Spring MVC's `ResponseEntityExceptionHandler` and maps Bean
Validation and conversion failures, unreadable bodies, `PricingNotFoundException`, method and
media errors, unmapped resources, other framework errors, and unexpected exceptions to the
catalogue in §6.2. Price Creation design §4.3 and §7 own its adapter/domain translation and
additional handler mappings.

The unexpected handler logs the request method/path and complete throwable server-side. It does
not log request bodies or credentials. Responses never serialize an exception class, stack trace,
SQL, constraint name, JDBC URL, username, password, or environment value.

## 7. OpenAPI and Swagger

### 7.1 Generation and endpoints

Springdoc generates JSON at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`. Scanning is
limited to `/api/v1/**`, excluding Actuator. `OpenApiConfig` declares title
`RentFlow Pricing API`, version `v1`, a Pricing tag, and no security scheme.

### 7.2 Operations and schemas

Stable operation IDs owned by this skeleton are:

| Operation | Operation ID |
| --- | --- |
| List | `listPricing` |
| Retrieve | `getPricing` |
| Replace | `replacePricing` |
| Delete | `deletePricing` |

DTO annotations describe all six required properties, serial pattern, decimal precision and
bounds, and the representative example from §2.3. The collection return type generates a typed
`PagedModel<PricingDTO>` with `content` and nested `page`. Controller annotations describe query
defaults/bounds, success responses, and applicable problems. `PATCH` is absent.

The `createPricing` operation ID and creation-specific responses are owned by Price Creation
design §9.

### 7.3 Contract consistency

`OpenApiIT` requests `/v3/api-docs` and checks the four skeleton-owned operations and IDs; the
six exact business properties; validation constraints; paging and sorting parameters; the page
envelope; applicable success and error statuses; examples; absence of Actuator, `PATCH`, and
security schemes; and resolution of every referenced component schema. The same generated
document contains `createPricing`; Price Creation design §9 and §10.2 own its assertions.

## 8. Configuration and health

### 8.1 Application configuration

`application.yaml` contains non-secret invariants:

```yaml
spring:
  application:
    name: rent-flow-pricing
  jackson:
    deserialization:
      fail-on-unknown-properties: true
  flyway:
    create-schemas: false
    default-schema: pricing
    schemas: pricing
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        default_schema: pricing
  sql:
    init:
      mode: never
```

Only health is exposed through Actuator. Probe support and additional paths are enabled;
liveness includes only `livenessState`, readiness includes `readinessState,db`, and details are
hidden. The application contains no datasource URL or credentials. Deployments provide
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, optional
`SERVER_PORT`, and optional `JAVA_TOOL_OPTIONS`.

Inventory HTTP and Resilience4j configuration are feature-specific and are defined in Price
Creation design §4-§8.

### 8.2 Liveness and readiness

`/actuator/health/liveness` and `/livez` return `200` with `UP` for a live process and do not
depend on PostgreSQL. `/actuator/health/readiness` and `/readyz` include the database indicator.
Database loss after startup therefore returns `503` for readiness while liveness remains `200`.

### 8.3 Startup failures

Flyway and Hibernate validation complete during context startup. Database connectivity, schema,
privilege, migration, checksum, or validation failures abort startup rather than exposing a
partially initialized or ready application.

## 9. Container design

### 9.1 Application image

The multi-stage root `Dockerfile` uses `eclipse-temurin:25.0.3_9-jdk-noble` to run
`./mvnw -B -ntp package` and `eclipse-temurin:25.0.3_9-jre-noble` for runtime. Packaging runs unit
tests without skip flags; full Testcontainers verification remains the host lifecycle's job.

The runtime creates user/group `pricing` at UID/GID `10001`, owns `/opt/pricing`, copies only
`pricing.jar`, exposes `8080`, checks `/readyz`, and runs
`java -jar /opt/pricing/pricing.jar`. It contains no source, compiler, Maven installation/cache,
or build credential. `.dockerignore` excludes repository metadata, specs, IDE files, target,
local environment files, and other non-build input.

### 9.2 Docker Compose topology

`compose.yaml` defines `rentflow-postgres` from `postgres:18.4-alpine` and `pricing` from the local
Dockerfile. Development-only defaults are database `rentflow`, administrator
`rentflow_admin`/`rentflow-admin-local`, and Pricing role `pricing`/`pricing-local`.

`docker/postgres/init-pricing.sh` uses identifier-safe PostgreSQL formatting to create only the
login role and role-owned `pricing` schema on first initialization. It prints no password and
creates no application table or Flyway history. The application receives its datasource through
`SPRING_DATASOURCE_*`, depends on the database health check, publishes HTTP `8080`, and the
database binds only `127.0.0.1:${POSTGRES_PORT:-5432}`. The named volume
`rentflow-postgres-data` persists `/var/lib/postgresql`.

### 9.3 Container lifecycle

`docker compose up --build` starts PostgreSQL, applies bootstrap prerequisites, starts Pricing,
applies Flyway, and marks Pricing healthy through readiness. Restarting `pricing` or running
`docker compose down` preserves data. `docker compose down --volumes` is the documented destructive
reset. Stopping PostgreSQL makes readiness fail without changing liveness; restart permits
readiness recovery without recreating Pricing.

## 10. Build and contributor experience

### 10.1 Maven dependencies and lifecycle

Main foundation dependencies are focused Spring Boot Web MVC, Validation, Data JPA, Flyway,
Actuator, Flyway's PostgreSQL module, the runtime PostgreSQL driver, and Springdoc. Price Creation
design §1.3 owns its RestClient and Resilience4j additions. Test dependencies are
Boot 4's focused Web MVC, Validation, Data JPA, and Flyway test starters plus Testcontainers
PostgreSQL/JUnit Jupiter and ArchUnit. There is no H2, Spring Security, Lombok, or MapStruct.

The build compiles release 25 with parameter metadata; repackages `pricing.jar`; runs `*Test`
through Surefire and `*IT` through Failsafe; enforces Java 25 and Maven 3.9; bans Spring Security;
and runs pinned Palantir formatting in `verify`. The single required lifecycle is
`mvn -B -ntp clean verify`; no skip flag is used.

### 10.2 Maven Wrapper and artifact

Maven Wrapper 3.3.4 uses script-only mode, Maven 3.9.16, and a distribution SHA-256 checksum.
`mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` are committed. Both installed Maven
and Wrapper commands execute the same lifecycle. The executable is `target/pricing.jar`.

### 10.3 README

The root README documents purpose and fields, Java/PostgreSQL versions, Docker prerequisite,
Wrapper and Maven builds, host and Compose startup/shutdown, destructive volume reset, local-only
credentials, schema ownership, runtime variables, migrations, health, Swagger/OpenAPI,
resource-operation and paginated/sorted list examples, verification, and the container smoke
test. Price Creation requirements AC4.3 and design §9 own creation-specific contract
documentation.

## 11. Verification design

### 11.1 Unit tests

JUnit Jupiter, AssertJ, Jakarta Validation, and Mockito tests run without a Spring context:

| Test | Focus |
| --- | --- |
| `PricingServiceTest` | Get, list, replace, delete, and missing paths |
| `PricingConverterTest` | Exact entity-to-DTO mapping |
| `PricingTest` | Immutable serial and mutable pricing details |
| `PricingDTOTest` | All validation boundaries and valid zero values |
| `ApiExceptionHandlerTest` | Stable, sanitized shared framework and unexpected failures |

Price Creation design §10.1 owns the create, Inventory adapter, Retry, and CircuitBreaker portions
of `PricingServiceTest` and the additional focused unit-test classes.

### 11.2 Integration tests

A shared support class starts `postgres:18.4-alpine`, database `rentflow`, and administrator
`rentflow_admin`. `src/test/resources/testcontainers/init-pricing.sql` creates the restricted
`pricing` role and schema; dynamic properties ensure Flyway, Hibernate, and API tests connect as
Pricing rather than the administrator.

`MigrationIT` proves V1 and schema-local history, database constraints, restricted ownership,
absence of objects in `public`, preservation of a sentinel object in another schema, repeated
startup/persistence, and startup failure when Pricing prerequisites are missing or inaccessible.
`PricingIT` uses full Spring MVC plus real PostgreSQL to prove retrieval, replacement,
deletion, validation, unknown JSON, pagination, all sorts and tie-breaking, shared errors, and
credential-free access. `OpenApiIT` implements §7.3. Price Creation design §10 owns the create,
Inventory, conflict, and resilience portions of these shared test classes. Database state is
cleared between tests.

### 11.3 Architecture tests

`ArchitectureTest` imports production classes and enforces §1.3, placement, naming, no cycles,
controller-to-repository prohibition, and exclusive controller ownership of MVC mappings.

### 11.4 Container smoke verification

`scripts/container-smoke-test.sh` validates Compose, builds the image, starts PostgreSQL, proves
the bootstrap created only the restricted role/schema prerequisites, starts Pricing, inspects its
unprivileged minimal runtime, verifies persistence across process and stack restarts, stops
PostgreSQL, observes readiness `503` and liveness `200`, restarts PostgreSQL, observes
recovery, and retrieves the same pricing. Its setup uses the feature-owned creation operation
defined in Price Creation design §2-§4. Price Creation design §10.3 documents why that setup is
currently incomplete, and Price Creation tasks T2 owns its repair. The script uses strict shell
mode, bounded polling, a cleanup trap, and never prints secrets.

## 12. Edge cases

| Case | Result |
| --- | --- |
| `DRILL-001` and `drill-001` | Distinct resources |
| Serial containing slash, whitespace, Unicode, or over 64 characters | `400` validation problem |
| Missing or null business field | `400`; no mutation |
| `price` or `weekendRate` equal to zero | `400`; strictly positive |
| Negative `deposit` or discount | `400` |
| `longRentalDiscount` equal to or above one | `400` |
| Too many fractional or integer digits | `400`; never rounded or truncated by the API |
| `longRentalCondition` zero, negative, fractional, or out of integer range | `400` |
| Unknown or repeated collection parameter | `400` |
| Page beyond the end | `200` with empty `content` and accurate metadata |
| Equal primary sort values | Ordered by `serialNumber ASC` tie-breaker |
| `PUT` body serial differs only by case | `400`; original row unchanged |
| `PUT` targets missing pricing | `404`; no upsert |
| Calculation-shaped path or payload | Unmapped or unknown-property error; no calculation |
| Flyway checksum mismatch or Pricing schema unavailable | Startup fails |
| Another service schema exists | Pricing migration leaves it unchanged |
| Database lost after startup | Readiness `503`, liveness `200`, API errors sanitized |

## 13. Requirements traceability

| Acceptance criteria | Design coverage |
| --- | --- |
| AC2.1-AC2.2 | §3.2, §4.3, §6 |
| AC3.1-AC3.5 | §2.3, §3.3, §4.3, §5.1, §12 |
| AC4.1-AC4.5 | §3.4, §4.4, §5.1-§5.3, §12 |
| AC5.1-AC5.3 | §3.5, §4.4, §5.2, §12 |
| AC6.1-AC6.6 | §2.1, §3.6, §6 |
| AC7.1-AC7.3 | §7 |
| AC8.1-AC8.6 | §4.1-§4.2, §8.1, §8.3, §11.2 |
| AC9.1-AC9.7 | §8.1-§8.2, §9, §11.4 |
| AC10.1-AC10.4 | §7.1, §8.1-§8.2, §10.2-§10.3 |
| AC11.1-AC11.6 | §10.1-§10.2, §11 |
| AC12.1-AC12.2 | §2.2 |

Every criterion has a design section and an observable verification path.

## 14. Primary references

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot build systems](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
- [Spring Boot Flyway initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Boot health probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
- [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Spring Data `PagedModel`](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/web/PagedModel.html)
- [Springdoc documentation](https://springdoc.org/v4/index.html)
- [Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)
- [PostgreSQL 18.4 release notes](https://www.postgresql.org/docs/release/18.4/)
- [PostgreSQL official container](https://hub.docker.com/_/postgres)
- [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [Testcontainers Java](https://java.testcontainers.org/)
- [ArchUnit](https://www.archunit.org/)
