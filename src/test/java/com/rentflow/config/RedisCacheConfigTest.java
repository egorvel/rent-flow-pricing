package com.rentflow.config;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.rentflow.model.PricingCacheEntry;
import com.rentflow.service.PricingCacheService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisCacheConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void createsTheTypedExpiringCacheWithoutConnectingToRedis() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner(connectionFactory).run(context -> {
            assertThat(context).hasNotFailed();
            RedisCacheManager cacheManager = context.getBean(RedisCacheManager.class);
            assertThat(cacheManager.getCacheNames()).containsExactly(PricingCacheService.CACHE_NAME);
            assertThat(cacheManager.getCache("undeclared")).isNull();
            RedisCache cache = (RedisCache) cacheManager.getCache(PricingCacheService.CACHE_NAME);
            assertThat(cache).isNotNull();
            RedisCacheConfiguration configuration = cache.getCacheConfiguration();
            assertThat(configuration.getAllowCacheNullValues()).isFalse();
            assertThat(configuration.isTimeToIdleEnabled()).isFalse();
            assertThat(configuration.getKeyPrefixFor(PricingCacheService.CACHE_NAME))
                    .isEqualTo("rentflow:pricing:v1:pricingBySerial::");
            assertThat(configuration.getKeyPrefixFor(PricingCacheService.CACHE_NAME) + "DRILL-001")
                    .isEqualTo("rentflow:pricing:v1:pricingBySerial::DRILL-001");
            assertThat(configuration.getTtlFunction().getTimeToLive("DRILL-001", new Object()))
                    .isEqualTo(Duration.ofHours(1));
            ByteBuffer encodedKey = configuration.getKeySerializationPair().write("DRILL-001");
            assertThat(StandardCharsets.UTF_8.decode(encodedKey.duplicate()).toString())
                    .isEqualTo("DRILL-001");
            PricingCacheEntry entry = new PricingCacheEntry(
                    "DRILL-001",
                    new BigDecimal("12.34"),
                    new BigDecimal("1.25"),
                    7,
                    new BigDecimal("0.10"),
                    new BigDecimal("50.00"));
            ByteBuffer encodedValue = configuration.getValueSerializationPair().write(entry);
            assertThat(StandardCharsets.UTF_8.decode(encodedValue.duplicate()).toString())
                    .contains("\"serialNumber\":\"DRILL-001\"")
                    .doesNotContain(PricingCacheEntry.class.getName());
            assertThat(configuration.getValueSerializationPair().read(encodedValue.duplicate()))
                    .isInstanceOf(PricingCacheEntry.class)
                    .isEqualTo(entry);
            JacksonJsonRedisSerializer<PricingCacheEntry> valueSerializer =
                    context.getBean("pricingCacheValueSerializer", JacksonJsonRedisSerializer.class);
            RedisTemplate<String, PricingCacheEntry> redisTemplate =
                    context.getBean("pricingCacheRedisTemplate", RedisTemplate.class);
            assertThat(redisTemplate.getConnectionFactory()).isSameAs(connectionFactory);
            assertThat(redisTemplate.getKeySerializer()).isSameAs(StringRedisSerializer.UTF_8);
            assertThat(redisTemplate.getValueSerializer()).isSameAs(valueSerializer);
            assertThat(valueSerializer.deserialize(valueSerializer.serialize(entry)))
                    .isEqualTo(entry);
            assertThat(context.getBean(PricingCacheErrorHandler.class))
                    .isSameAs(context.getBean(RedisCacheConfig.class).errorHandler());
            verifyNoInteractions(connectionFactory);
        });
    }

    @Test
    void loadsRecommendedDefaultsAndEnvironmentOverrides() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner(connectionFactory)
                .withPropertyValues(
                        "PRICING_CACHE_TTL=2h",
                        "REDIS_HOST=redis.internal",
                        "REDIS_PORT=6380",
                        "REDIS_CONNECT_TIMEOUT=750ms",
                        "REDIS_COMMAND_TIMEOUT=300ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.data.redis.host"))
                            .isEqualTo("redis.internal");
                    assertThat(context.getEnvironment().getProperty("spring.data.redis.port"))
                            .isEqualTo("6380");
                    assertThat(context.getEnvironment().getProperty("spring.data.redis.connect-timeout"))
                            .isEqualTo("750ms");
                    assertThat(context.getEnvironment().getProperty("spring.data.redis.timeout"))
                            .isEqualTo("300ms");
                    RedisCache cache = (RedisCache)
                            context.getBean(RedisCacheManager.class).getCache(PricingCacheService.CACHE_NAME);
                    assertThat(cache.getCacheConfiguration().getTtlFunction().getTimeToLive("DRILL-001", new Object()))
                            .isEqualTo(Duration.ofHours(2));
                    assertThat(context.getEnvironment()
                                    .getProperty("management.endpoint.health.group.readiness.include"))
                            .isEqualTo("readinessState,db");
                    assertThat(context.getEnvironment()
                                    .getProperty("management.endpoint.health.group.liveness.include"))
                            .isEqualTo("livenessState");
                });
    }

    @Test
    void rejectsNonPositiveTimeToLive() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner(connectionFactory)
                .withPropertyValues("spring.cache.redis.time-to-live=0s")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pricing cache TTL must be positive"));
        contextRunner(connectionFactory)
                .withPropertyValues("spring.cache.redis.time-to-live=-1s")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pricing cache TTL must be positive"));
        contextRunner(connectionFactory)
                .withPropertyValues("spring.cache.redis.time-to-live=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pricing cache TTL must be positive"));
    }

    @Test
    void usesYamlCacheSettingsAndRejectsWrongCacheNames() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        contextRunner(connectionFactory)
                .withPropertyValues(
                        "spring.cache.redis.key-prefix=custom:", "spring.cache.redis.cache-null-values=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RedisCache cache = (RedisCache)
                            context.getBean(RedisCacheManager.class).getCache(PricingCacheService.CACHE_NAME);
                    RedisCacheConfiguration configuration = cache.getCacheConfiguration();
                    assertThat(configuration.getKeyPrefixFor(PricingCacheService.CACHE_NAME))
                            .isEqualTo("custom:pricingBySerial::");
                    assertThat(configuration.getAllowCacheNullValues()).isTrue();
                });
        contextRunner(connectionFactory)
                .withPropertyValues("spring.cache.cache-names=unexpected")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pricing cache names must contain only pricingBySerial"));
    }

    @Test
    void enablesCachingAtHighestPrecedence() {
        EnableCaching enableCaching = RedisCacheConfig.class.getAnnotation(EnableCaching.class);

        assertThat(enableCaching).isNotNull();
        assertThat(enableCaching.order()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private ApplicationContextRunner contextRunner(RedisConnectionFactory connectionFactory) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
                .withBean(RedisConnectionFactory.class, () -> connectionFactory)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(PricingCacheErrorHandler.class)
                .withUserConfiguration(RedisCacheConfig.class);
    }
}
