# Pricing Cache Design

Status: Implemented.

This document implements `requirements.md`. Numbered sections are stable traceability targets.
Shared pricing, persistence, error, and platform foundations are linked instead of repeated.

## 1. Scope and architecture

### 1.1 Specification ownership

This feature owns the Redis-backed cache-aside paths for retrieving one or a bounded batch of
pricing resources and the invalidation triggered by Pricing-owned mutations. The
[Service Skeleton design](../service-skeleton/design.md) remains authoritative for:

- the `PricingDTO` response shape and serial-number validation in §2.3 and §5.1;
- the PostgreSQL entity, repository, and transaction foundations in §1.4 and §4;
- authoritative `GET`, `PUT`, `DELETE`, list, and generic error behavior in §3 and §6;
- OpenAPI foundations in §7; and
- health, Compose, documentation, and verification foundations in §8-§11.

The [Price Creation design](../price-creation/design.md) remains authoritative for `POST`, its
Inventory validation, and its repository transaction boundary. This feature adds an
after-success cache invalidation to create without changing that ordering or holding a database
transaction across the Inventory call.

Only `GET /api/v1/pricing/{serialNumber}/cached` and
`POST /api/v1/pricing/batch/cached` use Redis for retrieval. The authoritative single-resource
endpoint, collection listing, and all write decisions continue to use PostgreSQL directly.

### 1.2 Components and packages

The implementation extends the existing packages without adding a new application layer:

| Package | Type | Responsibility |
| --- | --- | --- |
| `com.rentflow.controller` | `PricingController` | Bind and document single and batch cached retrieval |
| `com.rentflow.converter` | `PricingConverter` | Map both authoritative entities and cache entries to the shared `PricingDTO` response |
| `com.rentflow.service` | `PricingService` | Keep authoritative reads and evict the affected key after successful mutations |
| `com.rentflow.service` | `PricingCacheService` | Execute the proxied single lookup and bulk cache/database reads |
| `com.rentflow.model` | `PricingCacheEntry` | Immutable Redis payload and conversion boundary |
| `com.rentflow.config` | `RedisCacheConfig`, `PricingCacheErrorHandler` | Configure Redis caching and fail-open errors |
| `com.rentflow.repository` | `PricingRepository` | Load cache misses from PostgreSQL |

`PricingCacheEntry` is an internal model-layer value, not an HTTP DTO or JPA entity. Converters
already depend on model types. The config-to-model ArchUnit direction is extended narrowly so
`RedisCacheConfig` can select the typed serializer; all other layer directions remain unchanged.
`PricingCacheService` remains a separate Spring bean because proxy-mode cache annotations do not
intercept self-invocation. Mutation methods are called externally through the `PricingService`
proxy, so their `@CacheEvict` annotations are intercepted directly.

### 1.3 Runtime dependencies and versions

The Maven build adds only Boot-managed cache dependencies:

| Dependency | Role |
| --- | --- |
| `spring-boot-starter-cache` | Spring cache annotations and interception infrastructure |
| `spring-boot-starter-data-redis` | Spring Data Redis, `RedisCacheManager`, and Lettuce |

Spring Boot 4.1.0 manages the Spring Framework, Spring Data Redis, Lettuce, Jackson 3, and
Micrometer versions. No direct Jedis, Redisson, cache repository, distributed-lock, or JSON
library dependency is added. The existing Actuator dependency supplies Micrometer integration.

Local Compose and Redis integration tests use the exact image `redis:8.10.2-alpine`. Tests use
Testcontainers' existing `GenericContainer<?>`; there is no additional Redis-specific
Testcontainers module.

### 1.4 Request and mutation flows

Authoritative retrieval remains unchanged:

```text
GET /api/v1/pricing/{serialNumber}
  -> PricingController
  -> PricingService.get
  -> PricingRepository.findById
  -> PostgreSQL
  -> PricingConverter
  -> PricingDTO
```

Cached retrieval is cache-aside:

```text
GET /api/v1/pricing/{serialNumber}/cached
  -> PricingController
  -> PricingService.getCached
  -> proxied PricingCacheService.get
       -> Redis GET
          hit  -> deserialize PricingCacheEntry
          miss or suppressed Redis read failure
               -> PricingRepository.findById
               -> PostgreSQL result
               -> Redis SET with one-hour TTL
  -> PricingCacheEntry
  -> PricingConverter.toResponse(PricingCacheEntry)
  -> PricingDTO
```

Batch retrieval uses the same per-serial keys without a second cache:

```text
POST /api/v1/pricing/batch/cached
  -> validate the complete JSON array and deduplicate case-sensitively
  -> PricingService.getCachedBatch
  -> PricingCacheService.getBatch
       -> Redis MGET for distinct per-serial keys
       -> one PricingRepository.findAllById for misses
       -> best-effort per-key cache puts for positive database results
  -> PricingConverter.toResponse(PricingCacheEntry) for each result
  -> JSON array of PricingDTO (order unspecified)
```

Mutation invalidation is commit-aware:

```text
POST / PUT / DELETE
  -> cache advice around PricingService mutation
  -> existing PricingService mutation and PostgreSQL commit
  -> successful method return
  -> Redis DEL for the serial-number key
```

Create's `saveAndFlush` returns from its repository-managed transaction before the cache advice
evicts. Replace and delete use service transactions; the explicitly higher-precedence cache
advice wraps their transaction advice, so eviction follows a successful commit. A failed commit
does not evict.

## 2. HTTP contract

### 2.1 Authoritative retrieval

`GET /api/v1/pricing/{serialNumber}` retains operation ID `getPricing` and its existing behavior.
It invokes only `PricingService.get`, always consults PostgreSQL, never reads or populates Redis,
and remains available when Redis is absent or failing.

The endpoint returns the existing outcomes:

| Condition | Status and body |
| --- | --- |
| Valid serial with pricing | `200 OK` and `PricingDTO` |
| Invalid serial syntax | `400 Bad Request` Problem Details |
| Missing pricing | `404 Not Found` Problem Details |
| Unacceptable response type | `406 Not Acceptable` Problem Details |
| Unexpected PostgreSQL/application failure | Sanitized `500 Internal Server Error` Problem Details |

### 2.2 Cached retrieval

`GET /api/v1/pricing/{serialNumber}/cached` has operation ID `getCachedPricing`. The
`serialNumber` path variable uses the same case-sensitive pattern and error text as authoritative
retrieval. Successful and error media types, `PricingDTO`, and Problem Details are shared without
introducing a cache-specific response schema.

| Condition | Status and body | Data path |
| --- | --- | --- |
| Unexpired cache entry | `200 OK` and `PricingDTO` | Redis only |
| Cache miss and PostgreSQL row exists | `200 OK` and `PricingDTO` | PostgreSQL, then best-effort Redis population |
| Cache miss and PostgreSQL row is absent | `404 Not Found` Problem Details | PostgreSQL; no Redis value written |
| Redis read failure and PostgreSQL row exists | `200 OK` and `PricingDTO` | PostgreSQL fallback |
| Redis read failure and PostgreSQL row is absent | `404 Not Found` Problem Details | PostgreSQL fallback |
| Invalid serial syntax | `400 Bad Request` Problem Details | Neither data store |
| Unacceptable response type | `406 Not Acceptable` Problem Details | Normal MVC negotiation |
| PostgreSQL/application failure after a miss | Sanitized `500 Internal Server Error` Problem Details | Existing generic handler |

No `X-Cache`, `Age`, cache-specific field, query parameter, or alternate status is added. Cache
get, put, and eviction errors are not translated into client-visible failures.

### 2.3 Route precedence and compatibility

The two-segment `/{serialNumber}/cached` mapping is distinct from the existing one-segment
`/{serialNumber}` mapping and the collection mapping. `cached` remains a valid serial number for
the authoritative one-segment path; only a following `/cached` selects cached retrieval.

Existing callers, operation IDs, request validation, response examples, and write contracts do
not change. Cached retrieval is additive within API version `v1`.

### 2.4 Cached batch retrieval

`POST /api/v1/pricing/batch/cached` has operation ID `getCachedPricingBatch`, consumes
`application/json`, and produces `application/json`. The request body is a bare JSON array of
one to 100 case-sensitive serial-number strings, for example
`["DRILL-001","SAW-002"]`. The bound applies to the input element count before deduplication.
Each element must be a JSON string matching `PricingDTO.SERIAL_NUMBER_PATTERN`; null, blank,
non-string, and invalid-pattern elements are rejected. The controller validates the entire body
before any Redis or PostgreSQL lookup, then deduplicates case-sensitively.
`DRILL-001` and `drill-001` remain distinct.

A successful response is a bare JSON array of the existing six-field `PricingDTO` values, one
per distinct serial; array order is not guaranteed, and callers match entries by serial number.
There is no aggregate cache key, batch DTO,
source header, page wrapper, or calculated rental-price field.

| Condition | Status and body |
| --- | --- |
| Every distinct serial has pricing | `200 OK` and unordered `PricingDTO[]` |
| Any distinct serial missing from both cache and PostgreSQL | Existing `404 PRICING_NOT_FOUND` Problem Details naming any missing serial; no partial response |
| Invalid JSON or non-array/empty/oversized/invalid element | `400 Bad Request` Problem Details; no lookup |
| Unsupported request media type | Existing `415 Unsupported Media Type` Problem Details |
| Unacceptable response type | Existing `406 Not Acceptable` Problem Details |
| Unexpected PostgreSQL/application failure | Sanitized `500 Internal Server Error` Problem Details |

The controller uses the existing `RequestValidationException` for valid JSON with invalid
structure or elements, reporting the `serialNumbers` field or indexed element; malformed JSON
uses the existing malformed-body handler. The static `POST` mapping is distinct from existing
`GET`, create, and mutation mappings, so the serial `batch` remains retrievable through the
existing single-resource routes. Redis failures follow §4.3 and §5.5, not a new HTTP status.

## 3. Cache data model

### 3.1 Immutable payload

`PricingCacheEntry` is a public Java record in `com.rentflow.model` with the exact scalar fields:

```java
public record PricingCacheEntry(
        String serialNumber,
        BigDecimal price,
        BigDecimal weekendRate,
        Integer longRentalCondition,
        BigDecimal longRentalDiscount,
        BigDecimal deposit) {}
```

A compact constructor rejects a null field, and a `from(Pricing)` factory copies a loaded entity
into the record. `PricingConverter.toResponse(PricingCacheEntry)` maps that record directly to the
shared `PricingDTO`; no detached `Pricing` entity is constructed for a cache hit. Redis therefore
stores neither Hibernate state nor API validation/OpenAPI annotations. All six
fields are required; no timestamps, database metadata, type discriminator, or null sentinel are
stored.

### 3.2 Typed JSON serialization

Keys use UTF-8 `StringRedisSerializer`. Values use
`JacksonJsonRedisSerializer<PricingCacheEntry>` with Boot's Jackson 3 `ObjectMapper`. The typed
serializer avoids Java native serialization and does not enable polymorphic default typing.

The stored value is readable JSON with the six lower-camel-case fields and no Java class name, so
moving the record's package does not change the existing JSON format or require a new key
namespace. A pre-existing entry with that format remains readable. Decimal values remain
JSON numbers and deserialize directly to `BigDecimal`; application calculations never use their
textual Redis representation. On the single-item path, a malformed, incompatible, or wrong-type
payload produces a cache get error, which §4.3 treats as a miss; the PostgreSQL result then
replaces it when the subsequent cache put succeeds. On the batch path, §5.5's typed `MGET`
deserialization fails the whole cache read and deliberately skips cache puts for that request.

### 3.3 Cache name, key, and expiration

| Setting | Decision |
| --- | --- |
| Spring cache name | `pricingBySerial` |
| Redis key | `rentflow:pricing:v1:pricingBySerial::{serialNumber}` |
| Key comparison | Case-sensitive; no normalization |
| Expiration | Fixed TTL, default `1h` |
| TTL reset | On population/write only; reads do not extend it |
| Null values | Disabled |
| Negative entries | Absent; `PricingNotFoundException` is never cached |

The `rentflow:pricing:v1:` namespace prevents collision with other services and leaves a schema
version in the key. An incompatible future payload increments the namespace version rather than
attempting an in-place Redis migration; old keys disappear through their TTL. Cache-wide clear,
`KEYS`, and `SCAN` are not used by application flows. Both cached endpoints read and populate
these same per-serial keys.

The TTL is configured through `spring.cache.redis.time-to-live` with
`${PRICING_CACHE_TTL:1h}`. Tests override it without changing production code. A zero, negative,
or missing effective TTL is rejected during application configuration so no immortal pricing
entry can be created.

## 4. Spring and Redis configuration

### 4.1 Cache manager

`RedisCacheConfig` is a dedicated `@Configuration(proxyBeanMethods = false)` annotated with
`@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)`; caching is not enabled on
`PricingApplication`. Cache advice therefore returns a hit before transaction advice can begin a
read transaction, while a miss proceeds into the transactional method. Spring Boot
auto-configures the Lettuce `RedisConnectionFactory` from `spring.data.redis.*` and the
`RedisCacheManager` from `spring.cache.*`. The YAML in §4.2 is the sole source for the declared
cache name, fixed TTL, null-caching policy, versioned key prefix, and local statistics.

`RedisCacheConfig` does not declare a `CacheManager` bean. Its
`RedisCacheManagerBuilderCustomizer` validates the bound TTL is positive, retains Boot's
property-derived cache configuration, and replaces only the value serializer with typed Jackson
3 JSON. Boot declares the named cache before invoking builder customizers, so the customizer also
applies that typed configuration to each declared cache. The default key serializer is already
`StringRedisSerializer`. The customizer disables creation of undeclared caches and preserves the
non-locking writer with immediate writes for put and eviction. `CachingConfigurer` continues to
install the fail-open error handler from §4.3.

The same `JacksonJsonRedisSerializer<PricingCacheEntry>` bean configures a dedicated typed
`RedisTemplate<String, PricingCacheEntry>`. The batch path uses its `ValueOperations.multiGet`
instead of handling raw key/value bytes. Physical batch keys still use the prefix obtained from
the active `RedisCacheConfiguration`, so the template and annotation-driven cache address the
same entries without duplicating the prefix in Java.

`@Cacheable` uses its default `sync = false`. No locking `RedisCacheWriter`, distributed mutex,
Lua script, or local single-flight map is added. The application performs one-key `GET`, `SET`
with expiration, and `DEL` operations, plus `MGET` for batch retrieval.

### 4.2 Connection and timeout defaults

Spring Boot's blocking Redis support uses Lettuce with these tunable defaults:

```yaml
spring:
  cache:
    type: redis
    cache-names: pricingBySerial
    redis:
      time-to-live: ${PRICING_CACHE_TTL:1h}
      cache-null-values: false
      enable-statistics: true
      key-prefix: "rentflow:pricing:v1:"
      use-key-prefix: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:500ms}
      timeout: ${REDIS_COMMAND_TIMEOUT:200ms}
```

These `spring.cache.*` properties configure Boot's cache manager directly; no equivalent cache
name, prefix, null-caching, statistics, or TTL literal is maintained in production Java code.
The TTL validator reads the bound value but does not provide a second default. The exact key
remains `rentflow:pricing:v1:pricingBySerial::{serialNumber}`.

There is no Redis retry. One failed or timed-out cache command falls through according to §4.3.
Lettuce's shared thread-safe connection is used without adding Commons Pool. Production can use
Boot's supported username, password, URL, and SSL properties; credentials are not committed,
logged, or included in cache keys.

### 4.3 Fail-open error handling

Spring's default cache error handler rethrows provider exceptions, which would make Redis part of
the endpoint's availability contract. `PricingCacheErrorHandler` instead implements
`CacheErrorHandler` and suppresses runtime failures from cache get, put, evict, and clear
operations after recording them.

The operation-specific behavior is:

| Failed operation | Behavior after recording the failure |
| --- | --- |
| Get | Simulate a miss and execute the PostgreSQL-backed method |
| Put | Return the PostgreSQL result without caching it |
| Evict | Preserve the committed mutation response; existing entry expires by TTL |
| Clear | Preserve the caller's primary outcome; no feature flow invokes clear |

The handler never suppresses `PricingRepository`, conversion, controller, or programming failures
outside the Spring cache invocation. It does not log cache keys, cached values, Redis credentials,
or exception messages that could contain connection URLs. The exception class and bounded
operation name are sufficient diagnostics.

Because `sync = false`, a failed get that the handler converts to a miss is followed by the normal
method call and an independent best-effort put. This is required for PostgreSQL fallback.

### 4.4 Metrics and health

The eagerly declared cache is bound to Boot's standard Micrometer cache metrics at startup.
Statistics record local-instance hits, misses, puts, and evictions under standard `cache.*`
meters, tagged by cache name and cache-manager bean. `PricingCacheErrorHandler` increments
`pricing.cache.errors` with the bounded tag `operation=get|put|evict|clear`.

Every cache failure is logged at `DEBUG` without rate limiting, using only the bounded operation
name and exception class described in §4.3. Normal `INFO` logging therefore emits no cache-failure
events; enabling `DEBUG` for the handler emits one event per failure. The counter still records
every failure and is the intended high-volume outage signal once exported. This feature does not
configure a metrics exporter or alerting, and no cache metric or source is added to business HTTP
responses. The existing Actuator HTTP exposure remains health-only; a configured metrics
exporter can publish the registered meters without expanding the unauthenticated API surface.

The Redis health contributor may report Redis connectivity diagnostically, but Redis is excluded
from the readiness and liveness groups. Readiness remains `readinessState,db`, and liveness
remains `livenessState`. Redis unavailability therefore does not prevent startup or make the
service non-ready while PostgreSQL is usable.

## 5. Cached-read orchestration

### 5.1 Proxy boundary

`PricingController.getCachedPricing` calls `PricingService.getCached(serialNumber)`. That method
returns the `PricingCacheEntry` obtained from the separately injected
`PricingCacheService.get(serialNumber)`. The controller passes it to
`PricingConverter.toResponse(PricingCacheEntry)` without constructing a `Pricing` entity.

`PricingCacheService.get` is public and annotated with:

```java
@Cacheable(cacheNames = PricingCacheService.CACHE_NAME, key = "#serialNumber")
@Transactional(readOnly = true)
```

The delegation crosses a Spring bean proxy, so the cache interceptor runs. Calling an annotated
method from another method on the same bean is not used. `PricingService.get` remains the direct
repository lookup and has no cache annotation.

### 5.2 Hit, miss, and absence

On a hit, Spring deserializes and returns `PricingCacheEntry`; the method body and PostgreSQL
transaction do not run. On a miss, `PricingCacheService.get` invokes
`PricingRepository.findById`, throws the existing `PricingNotFoundException` when absent, or maps
the entity to `PricingCacheEntry` when present.

Spring caches only a normally returned entry. `PricingNotFoundException`, database exceptions,
and conversion exceptions escape the method and are not cached. A later request for the same
missing serial therefore performs another authoritative lookup.

### 5.3 TTL and staleness semantics

The fixed one-hour TTL starts whenever a successful miss result is written. Cache hits do not
renew it. Expiration is enforced by Redis; after expiry the next request follows the miss path.

Pricing API mutations attempt earlier invalidation through §6. Direct SQL changes, another writer
that bypasses `PricingService`, failed eviction, or the accepted race in §5.4 can leave a
value visible until TTL expiry. Consumers requiring the latest committed state use the
authoritative endpoint.

### 5.4 Concurrency and stampedes

Simultaneous misses for the same serial can each read PostgreSQL and write the same JSON value.
This is safe because the lookup is read-only, the key and payload are deterministic, and the last
equivalent write only resets the TTL. The design accepts this bounded duplicate work instead of
adding a cache-wide lock or distributed per-key lock.

A cached load can overlap a replacement or deletion as follows:

1. the load misses Redis and reads the old PostgreSQL row;
2. the mutation commits and evicts the key;
3. the older load writes its result after that eviction.

The resulting stale entry is bounded by the one-hour TTL, and authoritative retrieval remains
fresh. Preventing this race would require versioned rows, a distributed lock, or another
coordination mechanism and is outside this first increment. AC3.5 therefore guarantees the next
value only when no cache fill for that serial overlaps the mutation.

### 5.5 Batch orchestration and failure boundaries

`PricingService.getCachedBatch(List<String>)` remains non-transactional and delegates to
`PricingCacheService.getBatch`. The latter uses the configured `RedisCache` to derive the exact
key prefix, constructs the existing physical per-serial key strings, and passes them to a typed
`RedisTemplate<String, PricingCacheEntry>.opsForValue().multiGet(...)`. The template performs one
`MGET` and applies the same typed Jackson serializer as `RedisCache`; application code does not
serialize keys or deserialize JSON manually. Its ordered reply is paired with the corresponding
requested keys internally even though HTTP response order is unspecified. If any value cannot be
deserialized, the typed template fails the whole operation: the batch records one cache get and
decode error, treats every distinct serial as a miss, loads them in one PostgreSQL call, and skips
all cache puts for that request. Other batch read errors follow the same fallback but increment
the read-error meter.

The distinct miss set is loaded with one `PricingRepository.findAllById` call and mapped by
serial number because JPA does not promise result order. The repository call owns its read-only
transaction; no transaction spans Redis and PostgreSQL. All found database entries are written
through the configured Spring `Cache.put`, preserving typed JSON, TTL, and the existing keys,
including when another serial makes the response a `404`. If `MGET` or its typed deserialization
fails, all distinct serials are read from PostgreSQL and cache puts are skipped for that request.
If a put fails, its error is recorded and remaining puts are skipped. A database error propagates
normally.

After reading and optional warming, any missing serial produces the existing
`PricingNotFoundException` and one `PRICING_NOT_FOUND` response; which missing serial is named
is not guaranteed. No partial array or negative cache entry is returned. No batch-wide atomic
snapshot is promised: cached entries can have different ages within the existing TTL. The
existing standalone Redis keys are retained; cross-slot Redis Cluster `MGET` is out of scope.

Bulk calls bypass annotation-driven per-key cache statistics. Separate Micrometer meters record
`pricing.cache.batch.entries{outcome=hit|miss}` per distinct serial,
`pricing.cache.batch.errors{operation=read|decode|put}` per failed operation, and a
`pricing.cache.batch.size` summary for distinct batch size. These fixed tags never contain a
serial number. Existing `pricing.cache.errors` counters also record suppressed bulk read and
put failures, while single-item cache statistics retain their current meaning.

## 6. Mutation invalidation

### 6.1 Direct method eviction and transaction order

The public `PricingService.create`, `replace`, and `delete` methods carry `@CacheEvict` with
`beforeInvocation = false` (the default). Spring's cache interceptor therefore evicts only when
the proxied method completes normally. The keys come from the mutation arguments:

| Mutation | Eviction key | Transaction state at successful method return |
| --- | --- | --- |
| Create | `#pricing.serialNumber` | Repository `saveAndFlush` transaction already committed |
| Replace | `#replacement.serialNumber` | Service transaction already committed |
| Delete | `#serialNumber` | Service transaction already committed |

`@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)` in §4.1 makes cache advice wrap the
default-order transaction advice on `replace` and `delete`: enter cache advice, begin transaction,
run mutation, commit, then evict. A rollback or commit exception skips the post-invocation
eviction. This relies on these HTTP mutations owning their service transaction; a future caller
that wraps them in a larger transaction must revisit the eviction boundary.

### 6.2 Eviction operation

Each mutation method uses the same cache name with its argument-specific key:

```java
@CacheEvict(cacheNames = PricingCacheService.CACHE_NAME, key = "#serialNumber")
```

The service is called through its Spring bean proxy, so the cache interceptor issues one `DEL`
after a successful proxied invocation. Create evicts defensively because a serial number might have
a stale legacy entry even though negative results are not cached. Replace and delete remove the
previous positive entry.

No write path updates Redis with its response object. Eviction keeps PostgreSQL authoritative and
lets the next cached request populate one representation through the same miss path.

### 6.3 Invalidation failures and process loss

`PricingCacheErrorHandler` suppresses an eviction failure after recording it. The already
committed create, replacement, or deletion retains its existing HTTP status and body. The stale
entry remains bounded by TTL, while the authoritative endpoint exposes current PostgreSQL state.

The eviction is local and non-durable. A process crash after PostgreSQL commit but before eviction
has the same bounded-staleness outcome as a Redis eviction failure. An outbox, Redis transaction,
two-phase commit, and compensating database write are not introduced because Redis is explicitly
non-authoritative.

## 7. Container and environment design

Compose adds `rentflow-redis` using `redis:8.10.2-alpine` with a `redis-cli ping` health check. It
has no persistence volume because cache contents are disposable. If the Redis port is published
for local inspection, it binds only to `127.0.0.1:${REDIS_PORT:-6379}`; the unauthenticated local
container is never exposed on all interfaces.

The Pricing container receives `REDIS_HOST=rentflow-redis` and `REDIS_PORT=6379`. Pricing does not
use a `service_healthy` dependency on Redis: it may start concurrently, fall back to PostgreSQL,
and recover cache use when Redis becomes reachable. PostgreSQL remains the only readiness
dependency.

The local Redis container has no password or TLS. Deployed credentials, TLS, persistence,
replication, Sentinel, Cluster, backup, and provisioning remain platform concerns configured
through environment-specific Spring properties.

## 8. OpenAPI and documentation

The controller annotations add `getCachedPricing` with the shared serial parameter and
`getCachedPricingBatch` with a bounded serial-array body. Both reuse `PricingDTO`/Problem Details
schemas. The single endpoint documents `200`, `400`, `404`, `406`, and `500`; the batch endpoint
also documents `415`. Redis failure is documented as PostgreSQL fallback, not as an additional
response status.

The README adds:

- the choice between authoritative and cached endpoints;
- executable `curl` examples for both;
- the fixed, configurable one-hour TTL;
- API mutation invalidation and the overlapping-fill limitation;
- fallback and readiness behavior during Redis failure;
- Redis host, port, timeout, and TTL environment variables;
- local Compose startup and Redis inspection commands;
- the fact that no cache-source header is returned; and
- an executable batch request with the 100-input limit, case-sensitive deduplication,
  unspecified output order and missing-serial selection, all-or-nothing `404`, and shared
  per-serial cache behavior.

No Redis credential or production topology example is committed.

## 9. Verification design

### 9.1 Unit tests

Unit tests use JUnit, AssertJ, and Mockito without a Spring context:

| Test | Assertions |
| --- | --- |
| `PricingCacheServiceTest` | Miss loads once, maps all six values, and missing pricing throws without producing a value |
| `PricingCacheEntryTest` | Entity-to-entry conversion preserves identity, decimal values, and scale-insensitive value equality; every null component is rejected |
| `PricingConverterTest` | Direct cache-entry-to-DTO conversion preserves all six fields and matches the authoritative response |
| `PricingCacheErrorHandlerTest` | Get, put, evict, and clear failures are suppressed and increment only their bounded error counter |
| `PricingServiceTest` | Authoritative get still uses the repository; cached get delegates; mutations retain their existing behavior |
| `PricingCacheEvictionAnnotationsTest` | All three write methods evict only their serial key after successful invocation |
| `PricingServiceTest` batch cases | Delegation to the bulk cache method without a batch-wide transaction |
| `PricingCacheServiceTest` batch cases | One MGET, zero-or-one bulk database call, per-key warming, fail-open behavior, and bounded metrics |
| `PricingControllerTest` batch cases | Full-body validation before lookup, complete unordered DTOs, and existing Problem Details contracts |

Tests call production methods with explicit Java types and verify collaborators rather than
starting Spring merely to exercise business orchestration.

### 9.2 Redis integration tests

`PricingCacheIT` starts PostgreSQL 18.4 and `redis:8.10.2-alpine` Testcontainers and configures the
application through `@DynamicPropertySource`. It verifies externally observable HTTP responses,
Redis keys and TTL, and repository spy interactions:

- the first cached retrieval reads PostgreSQL and creates the exact versioned key;
- the second retrieval returns the same representation without another repository lookup;
- the stored JSON has all six fields and is not Java native serialization;
- a pre-existing six-field JSON entry remains readable at the unchanged key without consulting PostgreSQL;
- a forced short test TTL expires and causes the next retrieval to reload PostgreSQL;
- repeated missing retrievals each consult PostgreSQL and create no Redis key;
- create, replace, and delete evict the affected key only after successful completion;
- a failed replacement or deletion leaves an existing cache entry intact;
- cache metrics record hits, misses, and evictions without adding HTTP headers; and
- an out-of-band PostgreSQL update remains hidden on cached retrieval but is visible through the
  authoritative endpoint until expiry or eviction;
- single and batch endpoints share keys in both directions; a mixed batch uses one `MGET` and
  one bulk PostgreSQL call for misses without renewing hit TTLs;
- a batch with an invalid body performs no lookup, while any missing serial returns the existing
  `404` without a negative entry and all found prices may still be warmed; and
- batch retrieval falls back with one bulk PostgreSQL call during Redis outages, skips repeated
  cache writes, and reflects API mutation eviction.

`PricingCacheUnavailableIT` starts a real Redis Testcontainer, warms the application, then stops
that container before exercising one end-to-end outage scenario. It proves authoritative
retrieval is unaffected, cached retrieval falls back to PostgreSQL, not-found remains `404`,
writes retain their normal success responses when eviction fails, and
`pricing.cache.errors` records the failures. The class owns an isolated Spring context and does
not restart or share the stopped container with another test.

`PricingCacheStartupWithoutRedisIT` configures an unused local port with the production timeouts
and proves the application starts and reports ready while Redis is unreachable. It does not
replace the stopped-real-container outage coverage.

### 9.3 Contract, architecture, and container tests

`OpenApiIT` asserts both cached paths, operation IDs, batch array request/response schemas,
parameter constraint, shared schemas, and documented responses. `ArchitectureTest` permits
config to depend on model for typed cache serialization while continuing to enforce all other
layer directions, component suffixes,
package cycles, and controller-only MVC mappings.

The container smoke test starts Redis with the isolated stack, verifies Pricing readiness does
not include Redis, warms one cache key, and proves cached retrieval survives a PostgreSQL-neutral
second read. Redis data is not expected to survive Redis or stack recreation.

The final implementation must pass `mvn -B -ntp clean verify` with Java 25 and Docker available.

## 10. Requirements traceability

| Requirement | Design coverage |
| --- | --- |
| AC1.1-AC1.3 | §2.1, §4.2-§4.3, §5.1 |
| AC2.1-AC2.3 | §2.2, §5.1-§5.2 |
| AC2.4-AC2.5 | §2.2-§2.3, §4.4 |
| AC3.1-AC3.3 | §3.3, §5.3 |
| AC3.4-AC3.5 | §5.4, §6.1-§6.2 |
| AC4.1-AC4.4 | §4.2-§4.4, §6.3, §7 |
| AC5.1 | §8, §9.3 |
| AC5.2 | §8 |
| AC5.3 | §7, §9.3 |
| AC5.4 | §9.1-§9.2 |
| AC5.5 | §9.3 |
| AC6.1-AC6.4 | §2.4, §3.3, §5.5 |
| AC6.5-AC6.6 | §2.4, §4.3, §5.5 |
| AC6.7-AC6.8 | §8, §9.3 |
| AC7.1-AC7.6 | §3.2-§3.3, §5.5, §9.1-§9.2 |

No requirement changes the PostgreSQL schema or requires a Flyway migration.
