package com.rentflow.config;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Map<Operation, LogWindow> logWindows = new EnumMap<>(Operation.class);
    private final LongSupplier nanoTime;

    @Autowired
    public PricingCacheErrorHandler(MeterRegistry meterRegistry, ObservationRegistry observationRegistry) {
        this(meterRegistry, observationRegistry, System::nanoTime);
    }

    PricingCacheErrorHandler(
            MeterRegistry meterRegistry, ObservationRegistry observationRegistry, LongSupplier nanoTime) {
        this.observationRegistry = observationRegistry;
        this.nanoTime = nanoTime;
        errorCounters = new EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            Counter counter = Counter.builder("pricing.cache.errors")
                    .description("Suppressed pricing cache provider errors")
                    .tag("operation", operation.tag)
                    .register(meterRegistry);
            errorCounters.put(operation, counter);
            logWindows.put(operation, new LogWindow());
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
        Long suppressed = logWindows.get(operation).next(nanoTime.getAsLong());
        if (suppressed != null) {
            LOGGER.atWarn()
                    .addKeyValue("cache", "pricingBySerial")
                    .addKeyValue("operation", operation.tag)
                    .addKeyValue("exceptionType", exception.getClass().getName())
                    .addKeyValue("suppressedCount", suppressed)
                    .setCause(exception)
                    .log("Redis cache operation failed; continuing without cache");
        }
    }

    private static final class LogWindow {
        private static final long INTERVAL = Duration.ofSeconds(60).toNanos();
        private boolean emitted;
        private long lastEmission;
        private long suppressed;

        synchronized Long next(long now) {
            if (!emitted || now - lastEmission >= INTERVAL) {
                long count = suppressed;
                suppressed = 0;
                emitted = true;
                lastEmission = now;
                return count;
            }
            suppressed++;
            return null;
        }
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
