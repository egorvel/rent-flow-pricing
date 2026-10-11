package com.rentflow.config;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;

import com.rentflow.service.PricingCacheLookupContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

@Component
public class PricingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PricingCacheErrorHandler.class);

    private final Map<Operation, Counter> errorCounters;
    private final ObservationRegistry observationRegistry;

    public PricingCacheErrorHandler(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        errorCounters = new EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            Counter counter = Counter.builder("pricing.cache.errors")
                    .description("Suppressed pricing cache provider errors")
                    .tag("operation", operation.tag)
                    .register(meterRegistry);
            errorCounters.put(operation, counter);
        }
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        record(Operation.GET, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        record(Operation.PUT, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        record(Operation.EVICT, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        record(Operation.CLEAR, exception);
    }

    private void record(Operation operation, RuntimeException exception) {
        errorCounters.get(operation).increment();
        PricingCacheLookupContext.failed(observationRegistry, operation.tag, exception);
        LOGGER.debug(
                "Redis cache {} operation failed; continuing without cache ({})",
                operation.tag,
                exception.getClass().getName());
    }

    private enum Operation {
        GET("get"),
        PUT("put"),
        EVICT("evict"),
        CLEAR("clear");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }
    }
}
