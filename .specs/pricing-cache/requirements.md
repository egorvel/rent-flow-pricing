# Pricing Cache Requirements

Status: Agreed; requirements, design, and implementation tasks defined; implementation pending.

## Context

Pricing consumers frequently retrieve one equipment item's pricing by serial number. PostgreSQL
remains the authoritative source, but serving every popular read from PostgreSQL adds avoidable
database work when prices change infrequently. This feature adds a Redis-backed retrieval option
for consumers that accept bounded staleness while preserving the existing retrieval endpoint for
consumers that require the latest committed pricing.

The two retrieval choices intentionally provide different consistency guarantees:

| Consumer need | Endpoint | Guarantee |
| --- | --- | --- |
| Latest committed pricing | `GET /api/v1/pricing/{serialNumber}` | Reads PostgreSQL and does not return a cached representation |
| Popular, read-heavy access | `GET /api/v1/pricing/{serialNumber}/cached` | May return a Redis copy that is at most one configured TTL old |

Redis is an acceleration layer, not a system of record. A cache hit means Redis already contains a
positive pricing representation for the serial number. A cache miss means that no usable entry is
present, so Pricing reads PostgreSQL and attempts to retain the successful result for later
requests. Each retained entry has a time to live (TTL), after which Redis removes it and the next
cached request reads PostgreSQL again. Cache invalidation removes an entry early after Pricing
changes or deletes the authoritative row.

The default TTL is one hour. This is acceptable because prices are expected to change rarely,
Pricing invalidates entries after its own successful mutations, and callers can use the existing
endpoint whenever bounded staleness is not acceptable. A direct database change outside the
Pricing API bypasses invalidation and can therefore remain invisible to the cached endpoint until
the entry expires.

This feature owns only cached retrieval and its interaction with existing resource mutations:

| Capability | Contract owner |
| --- | --- |
| Cached single-resource retrieval | This feature |
| Cache TTL, population, invalidation, and degraded behavior | This feature |
| Authoritative single-resource retrieval and shared response representation | [Service Skeleton](../service-skeleton/requirements.md) |
| Create pricing | [Price Creation](../price-creation/requirements.md) |
| Replace and delete pricing | [Service Skeleton](../service-skeleton/requirements.md) |

## User stories

### US1 - Retrieve authoritative pricing

As a pricing consumer with a strict freshness requirement, I want the existing retrieval endpoint
to bypass cached values so that I receive the latest pricing committed to PostgreSQL.

- **AC1.1 (Event-driven):** When a client requests existing pricing through
  `GET /api/v1/pricing/{serialNumber}`, the pricing service shall read the authoritative value from
  PostgreSQL and return `200 OK` with all six business attributes without returning a Redis-cached
  representation.
- **AC1.2 (Unwanted):** If a client requests a missing serial number through the authoritative
  endpoint, then the pricing service shall return the existing `404 Not Found` Problem Details
  response.
- **AC1.3 (Unwanted):** If Redis is unavailable, slow, or misconfigured, then the pricing service
  shall continue to process authoritative retrieval according to its existing PostgreSQL-backed
  contract.

### US2 - Retrieve pricing through Redis

As a pricing consumer that accepts bounded staleness, I want a cached single-resource endpoint so
that popular reads can avoid repeated PostgreSQL access.

- **AC2.1 (Event-driven):** When a valid, unexpired Redis entry exists for the requested serial
  number, the pricing service shall return `200 OK` with that complete pricing representation
  without reading PostgreSQL.
- **AC2.2 (Event-driven):** When no usable Redis entry exists and PostgreSQL contains the requested
  pricing, the pricing service shall return `200 OK` with all six authoritative business
  attributes and shall attempt to cache that positive representation for subsequent requests.
- **AC2.3 (Unwanted):** If neither Redis nor PostgreSQL contains pricing for the requested serial
  number, then the pricing service shall return the existing `404 Not Found` Problem Details
  response and shall not cache the absence.
- **AC2.4 (Ubiquitous):** The cached endpoint shall use the same case-sensitive serial-number
  validation, successful response body, media type, and Problem Details contracts as the
  authoritative endpoint.
- **AC2.5 (Ubiquitous):** The pricing service shall not identify a cache hit, cache miss, or
  database fallback in the cached endpoint's response body or response headers.

### US3 - Bound stale pricing

As a pricing consumer, I want cached pricing to have a clear freshness bound so that I can choose
between lower database load and authoritative reads deliberately.

- **AC3.1 (Ubiquitous):** The pricing service shall assign every positive pricing cache entry a
  configurable TTL whose default value is one hour.
- **AC3.2 (State-driven):** While a positive cache entry is unexpired and has not been invalidated,
  the cached endpoint shall return it without checking PostgreSQL, including when
  PostgreSQL was changed outside the Pricing API after the entry was populated.
- **AC3.3 (Event-driven):** When a positive cache entry expires, the next cached retrieval for that
  serial number shall treat the entry as a cache miss and consult PostgreSQL.
- **AC3.4 (Event-driven):** When the pricing service successfully creates, replaces, or deletes
  pricing, it shall attempt to invalidate the Redis entry for the affected serial number.
- **AC3.5 (Event-driven):** When invalidation succeeds after a pricing replacement or deletion and
  no cache fill for that serial number overlaps the mutation, the next cached retrieval shall not
  return the pre-mutation representation.

### US4 - Continue serving reads during cache failures

As a service operator, I want Redis failures to degrade performance rather than availability so
that cached retrieval remains useful without making Redis another source of pricing outages.

- **AC4.1 (Unwanted):** If Redis cannot be read or times out during cached retrieval, then the
  pricing service shall fall back to PostgreSQL and return the same success or not-found outcome
  that an authoritative retrieval would return.
- **AC4.2 (Unwanted):** If Redis cannot retain a value after a successful PostgreSQL read, then the
  pricing service shall return the PostgreSQL result and shall not fail the client request because
  cache population failed.
- **AC4.3 (Unwanted):** If Redis invalidation fails after a PostgreSQL mutation has committed, then
  the pricing service shall preserve the mutation's existing success response; the stale cache
  entry may remain available only until its TTL expires, and the authoritative endpoint shall
  remain available for a fresh read.
- **AC4.4 (State-driven):** While Redis is unavailable, the pricing service shall continue to
  process creation, authoritative retrieval, cached retrieval through PostgreSQL fallback,
  replacement, deletion, and collection listing without requiring Redis recovery.

### US5 - Publish and verify the caching contract

As an API consumer or service operator, I want the two freshness choices and Redis behavior to be
documented and automatically verified so that I can use and operate caching deliberately.

- **AC5.1 (Ubiquitous):** The Pricing OpenAPI document shall describe the cached retrieval
  operation, its path parameter, `200`, `400`, `404`, `406`, and `500` responses, and the shared
  pricing and Problem Details schemas.
- **AC5.2 (Ubiquitous):** The repository README shall document the authoritative and cached
  endpoints, the one-hour configurable default TTL, mutation invalidation, bounded-staleness
  trade-off, PostgreSQL fallback, and executable examples for both retrieval choices.
- **AC5.3 (Event-driven):** When the documented local Compose command is run, the stack shall start
  a Redis service and configure Pricing to use it without requiring manual cache provisioning.
- **AC5.4 (Event-driven):** When automated verification runs, the pricing service shall prove with
  a real Redis Testcontainer that cache hits avoid PostgreSQL, misses populate only
  successful results, entries expire, successful mutations invalidate entries, and Redis failures
  fall back to PostgreSQL.
- **AC5.5 (Event-driven):** When `mvn -B -ntp clean verify` runs in an environment satisfying the
  documented prerequisites, the build shall execute the caching unit, integration, OpenAPI, and
  architecture checks as part of the existing verification lifecycle.

## Out of scope

- Caching `GET /api/v1/pricing`, its paginated pages, or any write operation.
- Replacing PostgreSQL with Redis as the authoritative pricing store.
- Caching `404 Not Found` responses or any other failure response.
- Adding cache-source headers, fields, query parameters, or different success representations.
- HTTP intermediary caching through `Cache-Control`, validators, ETags, browsers, proxies, or a
  content-delivery network.
- Proactive cache warming, refresh-ahead, prefetching, or loading every pricing row at startup.
- Detecting direct or out-of-band PostgreSQL changes before an uninvalidated entry's TTL expires.
- Redis persistence, backup, replication, clustering, Sentinel, production infrastructure, or
  high-availability topology.
- Caching Inventory responses used during price creation.
- Rental-price calculation, currency policy, authentication, or authorization.

## Resolved questions

The following product decisions are resolved. Their implementation mechanisms and trade-offs are
justified in `design.md`.

1. **Cache scope:** Only single-pricing retrieval is cached; collection listing remains
   PostgreSQL-backed. See `design.md` §1.1 and §2.
2. **API choice:** The existing `GET /api/v1/pricing/{serialNumber}` remains authoritative, and
   `GET /api/v1/pricing/{serialNumber}/cached` provides the cache-enabled alternative. See
   `design.md` §2.
3. **Freshness:** Positive entries use a configurable TTL with a one-hour default. See
   `design.md` §3.3 and §5.3.
4. **Mutation coherence:** Successful creates, replacements, and deletions attempt to invalidate
   the affected entry after PostgreSQL commits. See `design.md` §6.
5. **Failure policy:** Redis read, population, or invalidation failures do not replace the
   PostgreSQL outcome; cached reads fall back to PostgreSQL and committed writes remain committed.
   See `design.md` §4.3 and §6.3.
6. **Negative caching:** Missing pricing is not cached. See `design.md` §5.2.
7. **Client visibility:** Responses do not reveal whether Redis or PostgreSQL supplied the result.
   See `design.md` §2.2 and §4.4.
