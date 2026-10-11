package com.rentflow;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingCacheUnavailableIT extends PostgresIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine")).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PricingRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void clearState() {
        repository.deleteAllInBatch();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        when(inventoryGateway.exists(anyString())).thenReturn(true);
    }

    @Test
    void stoppedRedisNeverChangesAuthoritativeCachedOrMutationContracts() throws Exception {
        save("OUTAGE-AUTH", "100.00");
        save("OUTAGE-CACHED", "125.00");
        save("OUTAGE-REPLACE", "150.00");
        save("OUTAGE-DELETE", "175.00");
        mockMvc.perform(get("/api/v1/pricing/OUTAGE-REPLACE/cached")).andExpect(status().isOk());

        REDIS.stop();
        assertThatThrownBy(() -> redisTemplate.hasKey("redis-is-stopped")).isInstanceOf(RuntimeException.class);

        double errorsBeforeAuthoritative = totalCacheErrors();

        mockMvc.perform(get("/api/v1/pricing/OUTAGE-AUTH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        mockMvc.perform(get("/api/v1/pricing/OUTAGE-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));
        assertThat(totalCacheErrors()).isEqualTo(errorsBeforeAuthoritative);

        double getErrorsBefore = cacheErrors("get");
        double putErrorsBefore = cacheErrors("put");
        double batchReadErrorsBefore = batchErrors("read");
        double singleLoadsBefore = databaseCount("loads", "single");
        double batchLoadsBefore = databaseCount("loads", "batch");
        double batchEntriesBefore = databaseCount("entries", "batch");
        mockMvc.perform(get("/api/v1/pricing/OUTAGE-CACHED/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(125.00))
                .andExpect(header().doesNotExist("X-Cache"))
                .andExpect(header().doesNotExist("Age"));
        mockMvc.perform(get("/api/v1/pricing/OUTAGE-MISSING/cached"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"OUTAGE-AUTH\",\"OUTAGE-CACHED\",\"OUTAGE-AUTH\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].serialNumber", containsInAnyOrder("OUTAGE-AUTH", "OUTAGE-CACHED")));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"OUTAGE-AUTH\",\"OUTAGE-MISSING\",\"OUTAGE-CACHED\"]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"));
        assertThat(cacheErrors("get")).isGreaterThanOrEqualTo(getErrorsBefore + 4.0);
        assertThat(cacheErrors("put")).isGreaterThanOrEqualTo(putErrorsBefore + 1.0);
        assertThat(batchErrors("read")).isEqualTo(batchReadErrorsBefore + 2.0);
        assertThat(databaseCount("loads", "single")).isEqualTo(singleLoadsBefore + 2);
        assertThat(databaseCount("loads", "batch")).isEqualTo(batchLoadsBefore + 2);
        assertThat(databaseCount("entries", "batch")).isEqualTo(batchEntriesBefore + 5);

        double evictErrorsBefore = cacheErrors("evict");
        double loadsBeforeWrites = databaseCount("loads", "single") + databaseCount("loads", "batch");
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("OUTAGE-CREATE", "200.00")))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/pricing/OUTAGE-REPLACE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("OUTAGE-REPLACE", "250.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(250.00));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"OUTAGE-REPLACE\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].price").value(250.00));
        mockMvc.perform(delete("/api/v1/pricing/OUTAGE-DELETE")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/pricing")).andExpect(status().isOk());
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(repository.findById("OUTAGE-CREATE")).isPresent();
        assertThat(repository.findById("OUTAGE-REPLACE").orElseThrow().getPrice())
                .isEqualByComparingTo("250.00");
        assertThat(repository.findById("OUTAGE-DELETE")).isEmpty();
        assertThat(cacheErrors("evict")).isGreaterThanOrEqualTo(evictErrorsBefore + 3.0);
        assertThat(databaseCount("loads", "single") + databaseCount("loads", "batch"))
                .isEqualTo(loadsBeforeWrites + 1);
    }

    private double databaseCount(String unit, String mode) {
        return meterRegistry
                .counter("pricing.cache.database." + unit, "mode", mode, "reason", "read_error")
                .count();
    }

    private double cacheErrors(String operation) {
        return meterRegistry
                .get("pricing.cache.errors")
                .tag("operation", operation)
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

    private double totalCacheErrors() {
        return meterRegistry.find("pricing.cache.errors").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private Pricing save(String serialNumber, String price) {
        return repository.saveAndFlush(pricing(serialNumber, price));
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
}
