package com.rentflow.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.rentflow.model.PricingCacheEntry;
import com.rentflow.service.PricingCacheService;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class RedisCacheConfig implements CachingConfigurer {

    private final PricingCacheErrorHandler errorHandler;

    public RedisCacheConfig(PricingCacheErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Bean
    public JacksonJsonRedisSerializer<PricingCacheEntry> pricingCacheValueSerializer(ObjectMapper objectMapper) {
        return new JacksonJsonRedisSerializer<>(objectMapper, PricingCacheEntry.class);
    }

    @Bean
    public RedisTemplate<String, PricingCacheEntry> pricingCacheRedisTemplate(
            RedisConnectionFactory connectionFactory,
            JacksonJsonRedisSerializer<PricingCacheEntry> pricingCacheValueSerializer) {
        RedisTemplate<String, PricingCacheEntry> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(StringRedisSerializer.UTF_8);
        redisTemplate.setValueSerializer(pricingCacheValueSerializer);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer pricingCacheManagerBuilderCustomizer(
            CacheProperties cacheProperties,
            RedisConnectionFactory connectionFactory,
            JacksonJsonRedisSerializer<PricingCacheEntry> pricingCacheValueSerializer) {
        Duration timeToLive = cacheProperties.getRedis().getTimeToLive();
        requirePositiveTimeToLive(timeToLive);
        if (!cacheProperties.getCacheNames().equals(List.of(PricingCacheService.CACHE_NAME))) {
            throw new IllegalArgumentException(
                    "Pricing cache names must contain only " + PricingCacheService.CACHE_NAME);
        }

        return builder -> {
            RedisCacheConfiguration cacheConfiguration = builder.cacheDefaults()
                    .serializeValuesWith(
                            RedisSerializationContext.SerializationPair.fromSerializer(pricingCacheValueSerializer));
            builder.withCacheConfiguration(PricingCacheService.CACHE_NAME, cacheConfiguration)
                    .cacheWriter(
                            RedisCacheWriter.create(connectionFactory, configurer -> configurer.immediateWrites(true)))
                    .disableCreateOnMissingCache();
        };
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return errorHandler;
    }

    private void requirePositiveTimeToLive(Duration timeToLive) {
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("Pricing cache TTL must be positive");
        }
    }
}
