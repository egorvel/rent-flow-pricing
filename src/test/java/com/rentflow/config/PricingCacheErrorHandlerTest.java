package com.rentflow.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PricingCacheErrorHandlerTest {

    @Test
    void suppressesEveryCacheOperationAndCountsOnlyBoundedOperationTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingCacheErrorHandler handler = new PricingCacheErrorHandler(meterRegistry);
        Cache cache = mock(Cache.class);
        RuntimeException failure = new IllegalStateException("redis://user:secret@cache/valuable-key");

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

    @Test
    void logsEachFailureOnlyAtDebugWithoutSensitiveDetails() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingCacheErrorHandler handler = new PricingCacheErrorHandler(meterRegistry);
        Cache cache = mock(Cache.class);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PricingCacheErrorHandler.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RuntimeException failure = new IllegalStateException("redis://user:secret@cache/valuable-key");

            logger.setLevel(Level.INFO);
            handler.handleCacheGetError(failure, cache, "valuable-key");
            assertThat(appender.list).isEmpty();

            logger.setLevel(Level.DEBUG);
            handler.handleCacheGetError(failure, cache, "valuable-key");
            handler.handleCacheGetError(failure, cache, "valuable-key");
            handler.handleCachePutError(failure, cache, "valuable-key", "value");
            handler.handleCacheEvictError(failure, cache, "valuable-key");
            handler.handleCacheClearError(failure, cache);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .containsExactly(
                            "Redis cache get operation failed; continuing without cache (java.lang.IllegalStateException)",
                            "Redis cache get operation failed; continuing without cache (java.lang.IllegalStateException)",
                            "Redis cache put operation failed; continuing without cache (java.lang.IllegalStateException)",
                            "Redis cache evict operation failed; continuing without cache (java.lang.IllegalStateException)",
                            "Redis cache clear operation failed; continuing without cache (java.lang.IllegalStateException)");
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(meterRegistry
                            .get("pricing.cache.errors")
                            .tag("operation", "get")
                            .counter()
                            .count())
                    .isEqualTo(3.0);
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
