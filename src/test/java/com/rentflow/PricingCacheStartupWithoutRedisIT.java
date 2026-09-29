package com.rentflow;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingCacheStartupWithoutRedisIT extends PostgresIntegrationTest {

    private static final int UNUSED_REDIS_PORT = findUnusedPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PricingRepository repository;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void registerUnavailableRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> UNUSED_REDIS_PORT);
    }

    @Test
    void startsReadyAndFallsBackWithinProductionTimeoutsWhenRedisNeverStarted() throws Exception {
        repository.saveAndFlush(new Pricing(
                "STARTUP-001",
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00")));

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/v1/pricing/STARTUP-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(125.50));
        mockMvc.perform(get("/api/v1/pricing/STARTUP-001/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(125.50));
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(cacheErrors("get")).isGreaterThanOrEqualTo(1.0);
        assertThat(cacheErrors("put")).isGreaterThanOrEqualTo(1.0);
    }

    private double cacheErrors(String operation) {
        return meterRegistry
                .get("pricing.cache.errors")
                .tag("operation", operation)
                .counter()
                .count();
    }

    private static int findUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not reserve a Redis test port", exception);
        }
    }
}
