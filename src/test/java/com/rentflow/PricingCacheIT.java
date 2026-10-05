package com.rentflow;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.PricingCacheService;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingCacheIT extends PostgresIntegrationTest {

    private static final Duration TEST_TTL = Duration.ofSeconds(5);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine")).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private PricingRepository repository;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.cache.redis.time-to-live", () -> TEST_TTL.toString());
    }

    @BeforeEach
    void clearState() {
        repository.deleteAllInBatch();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        redisCache().clearStatistics();
        reset(repository);
        when(inventoryGateway.exists(anyString())).thenReturn(true);
    }

    @Test
    void cachesTypedJsonUnderTheExactExpiringKeyAndServesTheSecondRequestWithoutPostgres() throws Exception {
        save("DRILL-001", "125.50");
        reset(repository);

        MvcResult first = mockMvc.perform(get("/api/v1/pricing/DRILL-001/cached"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.price").value(125.50))
                .andExpect(header().doesNotExist("X-Cache"))
                .andExpect(header().doesNotExist("Age"))
                .andReturn();

        String key = cacheKey("DRILL-001");
        String cachedJson = redisTemplate.opsForValue().get(key);
        Long initialTtl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(cachedJson).isNotNull().doesNotContain("PricingCacheEntry", "@class");
        JsonNode cachedEntry = objectMapper.readTree(cachedJson);
        assertThat(fieldNames(cachedEntry))
                .containsExactlyInAnyOrder(
                        "serialNumber", "price", "weekendRate", "longRentalCondition", "longRentalDiscount", "deposit");
        assertThat(cachedEntry.path("serialNumber").asString()).isEqualTo("DRILL-001");
        assertThat(initialTtl).isPositive().isLessThanOrEqualTo(TEST_TTL.toMillis());

        mockMvc.perform(get("/api/v1/pricing/DRILL-001/cached"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(first.getResponse().getContentAsByteArray()));

        verify(repository, times(1)).findById("DRILL-001");
        Long retainedTtl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(retainedTtl).isPositive().isLessThanOrEqualTo(initialTtl);
        CacheStatistics statistics = redisCache().getStatistics();
        assertThat(statistics.getGets()).isEqualTo(2);
        assertThat(statistics.getMisses()).isEqualTo(1);
        assertThat(statistics.getHits()).isEqualTo(1);
        assertThat(statistics.getPuts()).isEqualTo(1);
        assertThat(meterRegistry
                        .find("cache.gets")
                        .tag("cache", PricingCacheService.CACHE_NAME)
                        .meters())
                .isNotEmpty();
        assertThat(meterRegistry
                        .find("cache.puts")
                        .tag("cache", PricingCacheService.CACHE_NAME)
                        .meters())
                .isNotEmpty();
    }

    @Test
    void readsAnExistingJsonEntryAfterTheCacheEntryPackageMove() throws Exception {
        String existingJson = """
                {"serialNumber":"DRILL-001","price":125.50,"weekendRate":1.2500,
                 "longRentalCondition":7,"longRentalDiscount":0.1000,"deposit":300.00}
                """;
        redisTemplate.opsForValue().set(cacheKey("DRILL-001"), existingJson, TEST_TTL);

        mockMvc.perform(get("/api/v1/pricing/DRILL-001/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.price").value(125.50))
                .andExpect(jsonPath("$.deposit").value(300.00));

        verify(repository, never()).findById("DRILL-001");
    }

    @Test
    void neverCachesMissingPricing() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/MISSING/cached"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/pricing/MISSING/cached"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));

        verify(repository, times(2)).findById("MISSING");
        assertThat(redisTemplate.hasKey(cacheKey("MISSING"))).isFalse();
        assertThat(redisTemplate.keys("rentflow:pricing:v1:*")).isEmpty();
    }

    @Test
    void batchAndSingleReadsSharePerSerialKeysWithoutRenewingHitTtl() throws Exception {
        save("BATCH-WARM", "100.00");
        save("BATCH-MISS", "125.00");
        save("BATCH-LAST", "150.00");
        warm("BATCH-WARM");
        Long originalTtl = redisTemplate.getExpire(cacheKey("BATCH-WARM"), TimeUnit.MILLISECONDS);
        reset(repository);
        double hitsBefore = batchEntries("hit");
        double missesBefore = batchEntries("miss");
        double batchSizeBefore =
                meterRegistry.get("pricing.cache.batch.size").summary().totalAmount();

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"BATCH-WARM\",\"BATCH-MISS\",\"BATCH-WARM\",\"BATCH-LAST\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].serialNumber", containsInAnyOrder("BATCH-WARM", "BATCH-MISS", "BATCH-LAST")))
                .andExpect(jsonPath("$[?(@.serialNumber == 'BATCH-WARM')].price", hasItem(100.00)))
                .andExpect(jsonPath("$[?(@.serialNumber == 'BATCH-MISS')].price", hasItem(125.00)))
                .andExpect(jsonPath("$[?(@.serialNumber == 'BATCH-LAST')].price", hasItem(150.00)));

        verify(repository, never()).findById("BATCH-WARM");
        verify(repository, times(1)).findAllById(List.of("BATCH-MISS", "BATCH-LAST"));
        verify(repository, never()).findById("BATCH-MISS");
        verify(repository, never()).findById("BATCH-LAST");
        assertThat(redisTemplate.getExpire(cacheKey("BATCH-WARM"), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(originalTtl);
        assertThat(redisTemplate.keys("rentflow:pricing:v1:*"))
                .containsExactlyInAnyOrder(cacheKey("BATCH-WARM"), cacheKey("BATCH-MISS"), cacheKey("BATCH-LAST"));

        mockMvc.perform(get("/api/v1/pricing/BATCH-MISS/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(125.00));
        verify(repository, never()).findById("BATCH-MISS");
        assertThat(batchEntries("hit")).isEqualTo(hitsBefore + 1.0);
        assertThat(batchEntries("miss")).isEqualTo(missesBefore + 2.0);
        assertThat(meterRegistry.get("pricing.cache.batch.size").summary().totalAmount())
                .isEqualTo(batchSizeBefore + 3.0);
    }

    @Test
    void missingBatchSerialReturnsOneProblemAfterWarmingEveryFoundMiss() throws Exception {
        save("BATCH-FIRST", "100.00");
        save("BATCH-LATER", "200.00");
        reset(repository);

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"BATCH-FIRST\",\"BATCH-MISSING\",\"BATCH-LATER\"]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));

        verify(repository, times(1)).findAllById(List.of("BATCH-FIRST", "BATCH-MISSING", "BATCH-LATER"));
        verify(repository, never()).findById("BATCH-FIRST");
        verify(repository, never()).findById("BATCH-MISSING");
        verify(repository, never()).findById("BATCH-LATER");
        assertThat(redisTemplate.hasKey(cacheKey("BATCH-FIRST"))).isTrue();
        assertThat(redisTemplate.hasKey(cacheKey("BATCH-MISSING"))).isFalse();
        assertThat(redisTemplate.hasKey(cacheKey("BATCH-LATER"))).isTrue();

        mockMvc.perform(get("/api/v1/pricing/BATCH-FIRST/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        mockMvc.perform(get("/api/v1/pricing/BATCH-LATER/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200.00));
        verify(repository, never()).findById("BATCH-FIRST");
        verify(repository, never()).findById("BATCH-LATER");
    }

    @Test
    void corruptBatchEntryFallsBackForTheWholeBatchAndSkipsCacheWrites() throws Exception {
        save("BATCH-VALID", "100.00");
        save("BATCH-CORRUPT", "200.00");
        warm("BATCH-VALID");
        redisTemplate.opsForValue().set(cacheKey("BATCH-CORRUPT"), "not-json", TEST_TTL);
        reset(repository);
        double hitsBefore = batchEntries("hit");
        double missesBefore = batchEntries("miss");
        double decodeErrorsBefore = batchErrors("decode");

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"BATCH-VALID\",\"BATCH-CORRUPT\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].serialNumber", containsInAnyOrder("BATCH-VALID", "BATCH-CORRUPT")))
                .andExpect(jsonPath("$[?(@.serialNumber == 'BATCH-VALID')].price", hasItem(100.00)))
                .andExpect(jsonPath("$[?(@.serialNumber == 'BATCH-CORRUPT')].price", hasItem(200.00)));

        verify(repository, times(1)).findAllById(List.of("BATCH-VALID", "BATCH-CORRUPT"));
        verify(repository, never()).findById("BATCH-VALID");
        verify(repository, never()).findById("BATCH-CORRUPT");
        assertThat(batchEntries("hit")).isEqualTo(hitsBefore);
        assertThat(batchEntries("miss")).isEqualTo(missesBefore + 2.0);
        assertThat(batchErrors("decode")).isEqualTo(decodeErrorsBefore + 1.0);
        assertThat(redisTemplate.opsForValue().get(cacheKey("BATCH-CORRUPT"))).isEqualTo("not-json");
        assertThat(redisTemplate.opsForValue().get(cacheKey("BATCH-VALID"))).contains("BATCH-VALID");
    }

    @Test
    void replacementEvictsAnEntryWarmedByBatchRetrieval() throws Exception {
        save("BATCH-REPLACE", "100.00");
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"BATCH-REPLACE\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(100.00));
        assertThat(redisTemplate.hasKey(cacheKey("BATCH-REPLACE"))).isTrue();

        mockMvc.perform(put("/api/v1/pricing/BATCH-REPLACE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("BATCH-REPLACE", "200.00")))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey(cacheKey("BATCH-REPLACE"))).isFalse();

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"BATCH-REPLACE\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(200.00));
    }

    @Test
    void hitHidesOutOfBandUpdateUntilTheFixedTtlExpiresWhileAuthoritativeGetIsCurrent() throws Exception {
        save("DRILL-TTL", "100.00");
        reset(repository);
        mockMvc.perform(get("/api/v1/pricing/DRILL-TTL/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        Long originalTtl = redisTemplate.getExpire(cacheKey("DRILL-TTL"), TimeUnit.MILLISECONDS);

        Pricing persisted = repository.findById("DRILL-TTL").orElseThrow();
        persisted.replaceDetails(pricing("DRILL-TTL", "200.00"));
        repository.saveAndFlush(persisted);
        reset(repository);

        mockMvc.perform(get("/api/v1/pricing/DRILL-TTL/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        Long afterHitTtl = redisTemplate.getExpire(cacheKey("DRILL-TTL"), TimeUnit.MILLISECONDS);
        assertThat(afterHitTtl).isPositive().isLessThanOrEqualTo(originalTtl);
        verify(repository, times(0)).findById("DRILL-TTL");

        mockMvc.perform(get("/api/v1/pricing/DRILL-TTL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200.00));
        verify(repository, times(1)).findById("DRILL-TTL");

        awaitKeyExpiration(cacheKey("DRILL-TTL"));
        reset(repository);
        mockMvc.perform(get("/api/v1/pricing/DRILL-TTL/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200.00));
        verify(repository, times(1)).findById("DRILL-TTL");
        assertThat(redisTemplate.getExpire(cacheKey("DRILL-TTL"), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(TEST_TTL.toMillis());
    }

    @Test
    void committedMutationsEvictOnlyTheirAffectedKeys() throws Exception {
        save("DRILL-REPLACE", "100.00");
        save("DRILL-OTHER", "75.00");
        warm("DRILL-REPLACE");
        warm("DRILL-OTHER");

        redisTemplate.opsForValue().set(cacheKey("DRILL-CREATE"), "{\"stale\":true}", Duration.ofMinutes(1));
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("DRILL-CREATE", "125.50")))
                .andExpect(status().isCreated());
        assertThat(redisTemplate.hasKey(cacheKey("DRILL-CREATE"))).isFalse();
        mockMvc.perform(get("/api/v1/pricing/DRILL-CREATE/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(125.50));

        mockMvc.perform(put("/api/v1/pricing/DRILL-REPLACE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("DRILL-REPLACE", "200.00")))
                .andExpect(status().isOk());
        assertThat(redisTemplate.hasKey(cacheKey("DRILL-REPLACE"))).isFalse();
        assertThat(redisTemplate.hasKey(cacheKey("DRILL-OTHER"))).isTrue();
        mockMvc.perform(get("/api/v1/pricing/DRILL-REPLACE/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200.00));

        mockMvc.perform(delete("/api/v1/pricing/DRILL-REPLACE")).andExpect(status().isNoContent());
        assertThat(redisTemplate.hasKey(cacheKey("DRILL-REPLACE"))).isFalse();
        assertThat(redisTemplate.hasKey(cacheKey("DRILL-OTHER"))).isTrue();
        mockMvc.perform(get("/api/v1/pricing/DRILL-REPLACE/cached"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));

        assertThat(redisCache().getStatistics().getDeletes()).isGreaterThanOrEqualTo(3);
        assertThat(meterRegistry
                        .find("cache.removals")
                        .tag("cache", PricingCacheService.CACHE_NAME)
                        .meters())
                .isNotEmpty();
    }

    @Test
    void rolledBackReplacementAndDeletionLeaveWarmEntriesAndDatabaseRowsIntact() throws Exception {
        save("ROLLBACK-REPLACE", "100.00");
        save("ROLLBACK-DELETE", "150.00");
        warm("ROLLBACK-REPLACE");
        warm("ROLLBACK-DELETE");

        doAnswer(invocation -> {
                    String serialNumber = invocation.getArgument(0);
                    Pricing persisted = entityManager.find(Pricing.class, serialNumber);
                    if (serialNumber.startsWith("ROLLBACK-")) {
                        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                                .isTrue();
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("forced rollback");
                            }
                        });
                    }
                    return Optional.ofNullable(persisted);
                })
                .when(repository)
                .findById(anyString());

        mockMvc.perform(put("/api/v1/pricing/ROLLBACK-REPLACE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("ROLLBACK-REPLACE", "200.00")))
                .andExpect(status().isInternalServerError());
        mockMvc.perform(delete("/api/v1/pricing/ROLLBACK-DELETE")).andExpect(status().isInternalServerError());

        assertThat(redisTemplate.hasKey(cacheKey("ROLLBACK-REPLACE"))).isTrue();
        assertThat(redisTemplate.hasKey(cacheKey("ROLLBACK-DELETE"))).isTrue();
        reset(repository);
        mockMvc.perform(get("/api/v1/pricing/ROLLBACK-REPLACE/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        mockMvc.perform(get("/api/v1/pricing/ROLLBACK-DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(150.00));
    }

    private void warm(String serialNumber) throws Exception {
        mockMvc.perform(get("/api/v1/pricing/{serialNumber}/cached", serialNumber))
                .andExpect(status().isOk());
    }

    private void awaitKeyExpiration(String key) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (Boolean.TRUE.equals(redisTemplate.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    private void save(String serialNumber, String price) {
        repository.saveAndFlush(pricing(serialNumber, price));
    }

    private Pricing pricing(String serialNumber, String price) {
        return new Pricing(
                serialNumber,
                new BigDecimal(price),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }

    private String request(String serialNumber, String price) {
        return """
                {
                  "serialNumber": "%s",
                  "price": %s,
                  "weekendRate": 1.2500,
                  "longRentalCondition": 7,
                  "longRentalDiscount": 0.1000,
                  "deposit": 300.00
                }
                """.formatted(serialNumber, price);
    }

    private RedisCache redisCache() {
        return (RedisCache) cacheManager.getCache(PricingCacheService.CACHE_NAME);
    }

    private double batchEntries(String outcome) {
        return meterRegistry
                .get("pricing.cache.batch.entries")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private double batchErrors(String operation) {
        return meterRegistry
                .get("pricing.cache.batch.errors")
                .tag("operation", operation)
                .counter()
                .count();
    }

    private String cacheKey(String serialNumber) {
        return "rentflow:pricing:v1:pricingBySerial::" + serialNumber;
    }

    private Set<String> fieldNames(JsonNode node) {
        return node.propertyStream().map(entry -> entry.getKey()).collect(java.util.stream.Collectors.toSet());
    }
}
