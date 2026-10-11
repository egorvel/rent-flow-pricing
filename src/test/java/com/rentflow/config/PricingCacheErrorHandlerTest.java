package com.rentflow.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.mock.env.MockEnvironment;

import com.rentflow.support.EcsLogCapture;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PricingCacheErrorHandlerTest {

    @Test
    void limitsEachOperationIndependentlyAndSummarizesSuppressedErrorsAtExactlySixtySeconds() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicLong time = new AtomicLong();
        PricingCacheErrorHandler handler = new PricingCacheErrorHandler(meters, ObservationRegistry.NOOP, time::get);
        Cache cache = mock(Cache.class);
        RuntimeException failure =
                new IllegalStateException("Redis timed out", new RuntimeException("Connection closed"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "rent-flow-pricing")
                .withProperty("logging.structured.json.stacktrace.max-length", "16384");
        try (EcsLogCapture logs = new EcsLogCapture(PricingCacheErrorHandler.class.getName())) {
            MDC.put("traceId", "11111111111111111111111111111111");
            MDC.put("spanId", "2222222222222222");
            handler.handleCacheGetError(failure, cache, "valuable-key");
            time.set(Duration.ofSeconds(59).toNanos());
            handler.handleCacheGetError(failure, cache, "valuable-key");
            handler.handleCacheGetError(failure, cache, "valuable-key");
            handler.handleCachePutError(failure, cache, "valuable-key", "private-value");
            handler.handleCacheEvictError(failure, cache, "valuable-key");
            handler.handleCacheClearError(failure, cache);
            assertThat(logs.events()).hasSize(4);
            time.set(Duration.ofSeconds(60).toNanos());
            handler.handleCacheGetError(failure, cache, "valuable-key");
            assertThat(logs.events())
                    .hasSize(5)
                    .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
            JsonNode encoded = EcsLogCapture.encode(logs.events().getLast(), environment);
            assertThat(encoded.path("operation").asText()).isEqualTo("get");
            assertThat(encoded.path("suppressedCount").asLong()).isEqualTo(2);
            assertThat(encoded.path("traceId").asText()).isEqualTo("11111111111111111111111111111111");
            assertThat(encoded.path("spanId").asText()).isEqualTo("2222222222222222");
            assertThat(encoded.path("error").path("stack_trace").asText())
                    .contains("Redis timed out", "Connection closed");
            assertThat(encoded.toString()).doesNotContain("valuable-key", "private-value");
            assertThat(meters.get("pricing.cache.errors")
                            .tag("operation", "get")
                            .counter()
                            .count())
                    .isEqualTo(4);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    @Test
    void countsConcurrentFailuresExactlyWhileEmittingOneWarn() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PricingCacheErrorHandler handler = new PricingCacheErrorHandler(meters, ObservationRegistry.NOOP, () -> 0L);
        Cache cache = mock(Cache.class);
        try (EcsLogCapture logs = new EcsLogCapture(PricingCacheErrorHandler.class.getName());
                ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> results = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                results.add(workers.submit(() ->
                        handler.handleCacheGetError(new IllegalStateException("Redis unavailable"), cache, "key")));
            }
            for (Future<?> result : results) {
                result.get();
            }
            assertThat(meters.get("pricing.cache.errors")
                            .tag("operation", "get")
                            .counter()
                            .count())
                    .isEqualTo(200);
            assertThat(logs.events())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        }
    }

    @Test
    void suppressesEveryCacheOperationAndCountsOnlyBoundedOperationTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingCacheErrorHandler handler =
                new PricingCacheErrorHandler(meterRegistry, io.micrometer.observation.ObservationRegistry.NOOP);
        Cache cache = mock(Cache.class);
        RuntimeException failure = new IllegalStateException("Redis connection timed out");

        assertThatCode(() -> handler.handleCacheGetError(failure, cache, "key")).doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCachePutError(failure, cache, "key", "value"))
                .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheEvictError(failure, cache, "key"))
                .doesNotThrowAnyException();
        assertThatCode(() -> handler.handleCacheClearError(failure, cache)).doesNotThrowAnyException();

        assertThat(meterRegistry
                        .get("pricing.cache.errors")
                        .tag("operation", "get")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get("pricing.cache.errors")
                        .tag("operation", "put")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get("pricing.cache.errors")
                        .tag("operation", "evict")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get("pricing.cache.errors")
                        .tag("operation", "clear")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("pricing.cache.errors").counters()).hasSize(4);
    }
}
