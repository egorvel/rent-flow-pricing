# Price Creation Requirements

Status: Implemented except isolated container acceptance; tracked by `tasks.md` T2.

## Context

RentFlow Pricing must create pricing only for equipment that already exists in the Inventory
service. Creation therefore combines a local uniqueness check, a synchronous Inventory existence
lookup, and a database insert while presenting one stable HTTP contract to the caller.

This feature owns only the creation use case:

| Capability | Contract owner |
| --- | --- |
| `POST /api/v1/pricing` orchestration and outcomes | This feature |
| Inventory HTTP lookup, resilience, and response translation | This feature |
| Pricing representation and field validation | [Service Skeleton design §2.3 and §5.1](../service-skeleton/design.md#23-resource-representations) |
| Pricing entity, schema, and database constraints | [Service Skeleton design §4](../service-skeleton/design.md#4-persistence-design) |
| RFC 9457 response envelope and generic failures | [Service Skeleton design §6.1](../service-skeleton/design.md#61-rfc-9457-representation) |
| Authentication boundary, build, and platform operations | [Service Skeleton requirements](../service-skeleton/requirements.md) |

The shared definitions are normative for this feature and are referenced instead of repeated.
Where this document defines a creation-specific status, problem code, ordering rule, or resilience
policy, this document is the source of truth.

## User stories

### US1 - Create pricing atomically

As a pricing operator, I want to register pricing for an existing equipment item so that later
rental workflows can use its commercial terms without creating orphaned pricing.

- **AC1.1 (Event-driven):** When a client submits a complete pricing representation that satisfies
  the shared field constraints and Inventory confirms the exact `serialNumber`, the pricing service
  shall persist the six supplied business values and return `201 Created` with the created
  representation and `Location: /api/v1/pricing/{serialNumber}`.
- **AC1.2 (Ubiquitous):** The pricing service shall use the case-sensitive, client-supplied
  `serialNumber` as the public and persistence identifier and shall neither generate nor replace it.
- **AC1.3 (Unwanted):** If local pricing already exists for the submitted `serialNumber`, then the
  pricing service shall return `409 Conflict`, preserve the existing row, and make no Inventory
  request.
- **AC1.4 (Event-driven):** When concurrent valid create requests race for one unused
  `serialNumber`, the pricing service shall create at most one row and shall return
  `409 Conflict` to each request that loses the database uniqueness race.
- **AC1.5 (Unwanted):** If a create body is malformed, incomplete, contains an unknown property or
  wrong field type, or violates a shared field constraint, then the pricing service shall return
  `400 Bad Request`, persist no pricing, and make no Inventory request.

### US2 - Validate the Inventory reference

As a pricing maintainer, I want creation to confirm equipment identity with Inventory so that
Pricing does not retain terms for an unknown item.

- **AC2.1 (Event-driven):** When a locally unused, valid `serialNumber` is submitted, the pricing
  service shall request `GET /api/v1/inventory/{serialNumber}` before inserting pricing and shall
  treat only an exact `200 OK` response as confirmation that the item exists.
- **AC2.2 (Unwanted):** If Inventory returns `404 Not Found` for the submitted serial number, then
  the pricing service shall return `422 Unprocessable Content` with code
  `INVENTORY_ITEM_NOT_FOUND` and shall persist no pricing.
- **AC2.3 (Unwanted):** If Inventory returns `400 Bad Request` for the submitted serial number, then
  the pricing service shall return `400 Bad Request` with code
  `INVALID_INVENTORY_REFERENCE` and shall persist no pricing.
- **AC2.4 (Unwanted):** If Inventory returns another `4xx` response or a normal response status other
  than `200` or `404`, then the pricing service shall return `502 Bad Gateway` with code
  `INVENTORY_SERVICE_ERROR` and shall persist no pricing.
- **AC2.5 (Unwanted):** If the Inventory lookup ends with a network/resource-access failure, any
  `5xx` response, or rejection by an open circuit, then the pricing service shall return
  `503 Service Unavailable` with code `INVENTORY_SERVICE_UNAVAILABLE` and shall persist no
  pricing.

### US3 - Limit transient Inventory failures

As a service operator, I want bounded retries and circuit breaking around Inventory so that a
temporary upstream failure does not cause uncontrolled traffic or make Pricing fail indefinitely.

- **AC3.1 (Unwanted):** If the first Inventory attempt fails because of resource access or with
  `502`, `503`, or `504`, then the pricing service shall make exactly one delayed retry before
  translating the final outcome.
- **AC3.2 (Unwanted):** If an Inventory attempt returns another `5xx`, any `4xx`, or an
  unexpected normal response status, then the pricing service shall not retry that outcome.
- **AC3.3 (Ubiquitous):** The pricing service shall present the complete retry sequence to the
  Inventory circuit breaker as one logical call and shall count only resource-access and Inventory
  `5xx` exceptions as circuit-breaker failures.
- **AC3.4 (State-driven):** While the Inventory circuit is open, the pricing service shall reject
  create-time Inventory lookups without contacting Inventory and shall permit only the configured
  probe calls after the open-state wait expires.
- **AC3.5 (Ubiquitous):** The pricing service shall register retry and circuit-breaker measurements
  for the named `inventory` instances in the application meter registry.

### US4 - Publish the creation contract

As an API consumer, I want the generated contract to describe creation completely so that I can
distinguish local validation, Inventory rejection, and upstream availability failures.

- **AC4.1 (Ubiquitous):** The Pricing OpenAPI document shall describe `createPricing` with its
  shared request/success schemas, `Location` header, and the applicable `201`, `400`, `406`,
  `409`, `415`, `422`, `500`, `502`, and `503` responses.
- **AC4.2 (Event-driven):** When the application starts with valid Inventory configuration, the
  pricing service shall create the Inventory HTTP client and the named retry and circuit-breaker
  instances using the configured base URL, timeouts, and resilience defaults.
- **AC4.3 (Ubiquitous):** The repository README shall contain an executable
  `POST /api/v1/pricing` example using a representation that satisfies the shared field
  constraints.
- **AC4.4 (Event-driven):** When the isolated container smoke verification runs, the Pricing stack
  shall provide a deterministic Inventory response for its creation fixture and shall prove that
  the created pricing survives application and stack restarts.

## Out of scope

- Creating, updating, or deleting Inventory items.
- Parsing or persisting an Inventory response body; the lookup is an existence check only.
- Revalidating Inventory during pricing retrieval, listing, replacement, or deletion.
- Maintaining a cross-service foreign key, local Inventory cache, event replica, or distributed
  transaction.
- Automatically deleting or disabling pricing when an Inventory item is later removed.
- Retrying the client `POST` request or any non-idempotent Inventory operation; only the Inventory
  `GET` lookup is retried.
- Rental-price calculation, currency policy, authentication, and authorization, which remain
  outside this feature.

## Resolved questions

1. **Specification boundary:** Price creation owns `POST /api/v1/pricing`, the Inventory
   dependency, creation-specific failures, and resilience behavior. Shared resource and platform
   rules remain in `service-skeleton`. See `design.md` §1.
2. **Shared validation:** Creation reuses the shared `PricingDTO` representation and constraints
   rather than restating them. See `design.md` §2.1 and the Service Skeleton design §2.3 and §5.1.
3. **Inventory protocol:** Pricing sends one bodyless `GET` and treats only exact `200` as
   existence, `404` as absence, and every other outcome according to the translation table. See
   `design.md` §4.
4. **Duplicate ordering:** A local duplicate is rejected before Inventory is called; PostgreSQL
   remains the final authority for concurrent inserts. See `design.md` §3.1-§3.2.
5. **Failure mapping:** Inventory `400` maps to Pricing `400`, `404` maps to `422`, other
   protocol/client responses map to `502`, and availability failures map to `503`. See
   `design.md` §2.3 and §7.
6. **Retry selection:** Only resource-access failures and Inventory `502`/`503`/`504` are
   retried once. A predicate is used because a class-only exception list cannot select individual
   statuses within `HttpServerErrorException`. See `design.md` §5.
7. **Decorator order:** The circuit breaker wraps the retry so a complete attempt sequence is one
   logical circuit-breaker call. See `design.md` §6.1.
8. **Resilience values:** Numeric values are documented as tunable defaults, while bounded retry,
   failure classification, logical-call accounting, and fail-fast open-circuit behavior are stable
   policy. See `design.md` §5.2 and §6.2.
9. **Transaction boundary:** The remote Inventory call is not held inside a service-level database
   transaction; the final `saveAndFlush` runs through the repository transaction. See
   `design.md` §3.3.
10. **Metrics dependency:** Resilience measurements are registered through the Micrometer support
    brought transitively by `resilience4j-spring-boot4`; Pricing does not declare
    `resilience4j-micrometer` directly. See `design.md` §1.3 and §8.
11. **Container acceptance:** The isolated smoke topology must supply its own deterministic
    Inventory fixture rather than depend on a separately running RentFlow service. The current
    topology does not yet do so; completion is tracked explicitly. See `design.md` §10.3 and
    `tasks.md` T2.
