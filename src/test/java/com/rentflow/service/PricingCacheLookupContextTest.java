package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingCacheLookupContextTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();

    PricingCacheLookupContextTest() {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
    }

    @Test
    void findsTheLookupAcrossNestedFrameworkScopesAndRestoresItAfterFailure() {
        PricingCacheLookupContext.observe(observations, "single", () -> {
            Observation child = Observation.start("redis.command", observations);
            try (Observation.Scope ignored = child.openScope()) {
                PricingCacheLookupContext.failed(observations, "get", new SerializationException("bad JSON"));
                assertThat(PricingCacheLookupContext.readReason(observations)).isEqualTo("decode_error");
                PricingCacheLookupContext.loaded(observations);
            } finally {
                child.stop();
            }
            return "loaded";
        });
        assertThat(observations.getCurrentObservation()).isNull();
        assertThat(count("degraded")).isEqualTo(1);
        PricingCacheLookupContext.observe(observations, "single", () -> {
            assertThat(PricingCacheLookupContext.readReason(observations)).isEqualTo("miss");
            PricingCacheLookupContext.loaded(observations);
            return "loaded";
        });
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void recordsExpectedAbsenceAndUnexpectedFailureOnceAndAlwaysClosesScope() {
        assertThatThrownBy(() -> PricingCacheLookupContext.observe(observations, "single", () -> {
                    throw new PricingNotFoundException("MISSING");
                }))
                .isInstanceOf(PricingNotFoundException.class);
        assertThat(count("not_found")).isEqualTo(1);
        assertThatThrownBy(() -> PricingCacheLookupContext.observe(observations, "single", () -> {
                    throw new IllegalStateException("database unavailable");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("error")).isEqualTo(1);
        assertThat(observations.getCurrentObservation()).isNull();
    }

    @Test
    void isolatesConcurrentAndNestedLookupReasons() throws Exception {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                boolean failed = index % 2 == 0;
                results.add(workers.submit(() -> PricingCacheLookupContext.observe(observations, "single", () -> {
                    if (failed) {
                        PricingCacheLookupContext.failed(
                                observations, "get", new IllegalStateException("Redis unavailable"));
                    }
                    String before = PricingCacheLookupContext.readReason(observations);
                    PricingCacheLookupContext.observe(observations, "batch", () -> {
                        assertThat(PricingCacheLookupContext.readReason(observations))
                                .isEqualTo("miss");
                        return "hit";
                    });
                    assertThat(PricingCacheLookupContext.readReason(observations))
                            .isEqualTo(before);
                    return before;
                })));
            }
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(index % 2 == 0 ? "read_error" : "miss");
            }
        }
        assertThat(count("degraded")).isEqualTo(50);
        assertThat(count("hit")).isEqualTo(50);
        assertThat(observations.getCurrentObservation()).isNull();
    }

    private long count(String outcome) {
        return meters.get("pricing.cache.lookup")
                .tag("mode", "single")
                .tag("outcome", outcome)
                .timer()
                .count();
    }
}
