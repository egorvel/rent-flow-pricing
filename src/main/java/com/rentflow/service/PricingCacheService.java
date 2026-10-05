package com.rentflow.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class PricingCacheService {

    public static final String CACHE_NAME = "pricingBySerial";

    private final PricingRepository repository;
    private final CacheManager cacheManager;
    private final RedisTemplate<String, PricingCacheEntry> redisTemplate;
    private final CacheErrorHandler errorHandler;
    private final Counter batchHits;
    private final Counter batchMisses;
    private final Counter batchReadErrors;
    private final Counter batchDecodeErrors;
    private final Counter batchPutErrors;
    private final DistributionSummary batchSize;

    public PricingCacheService(
            PricingRepository repository,
            CacheManager cacheManager,
            @Qualifier("pricingCacheRedisTemplate") RedisTemplate<String, PricingCacheEntry> redisTemplate,
            CacheErrorHandler errorHandler,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        this.errorHandler = errorHandler;
        batchHits = meterRegistry.counter("pricing.cache.batch.entries", "outcome", "hit");
        batchMisses = meterRegistry.counter("pricing.cache.batch.entries", "outcome", "miss");
        batchReadErrors = meterRegistry.counter("pricing.cache.batch.errors", "operation", "read");
        batchDecodeErrors = meterRegistry.counter("pricing.cache.batch.errors", "operation", "decode");
        batchPutErrors = meterRegistry.counter("pricing.cache.batch.errors", "operation", "put");
        batchSize = DistributionSummary.builder("pricing.cache.batch.size")
                .description("Distinct serial numbers in a cached pricing batch")
                .register(meterRegistry);
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#serialNumber")
    @Transactional(readOnly = true)
    public PricingCacheEntry get(String serialNumber) {
        return repository
                .findById(serialNumber)
                .map(PricingCacheEntry::from)
                .orElseThrow(() -> new PricingNotFoundException(serialNumber));
    }

    public List<PricingCacheEntry> getBatch(List<String> serialNumbers) {
        batchSize.record(serialNumbers.size());
        RedisCache cache = (RedisCache) cacheManager.getCache(CACHE_NAME);
        RedisCacheConfiguration configuration = cache.getCacheConfiguration();
        List<String> keys = cacheKeys(serialNumbers, configuration);

        List<PricingCacheEntry> values;
        boolean cacheReadFailed = false;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null || values.size() != serialNumbers.size()) {
                throw new IllegalStateException("Redis MGET returned an incomplete result");
            }
        } catch (SerializationException exception) {
            errorHandler.handleCacheGetError(exception, cache, serialNumbers);
            batchDecodeErrors.increment();
            values = Collections.nCopies(serialNumbers.size(), null);
            cacheReadFailed = true;
        } catch (RuntimeException exception) {
            errorHandler.handleCacheGetError(exception, cache, serialNumbers);
            batchReadErrors.increment();
            values = Collections.nCopies(serialNumbers.size(), null);
            cacheReadFailed = true;
        }

        Map<String, PricingCacheEntry> found = new HashMap<>(serialNumbers.size());
        List<String> misses = new ArrayList<>(serialNumbers.size());
        for (int index = 0; index < serialNumbers.size(); index++) {
            String serialNumber = serialNumbers.get(index);
            PricingCacheEntry entry = values.get(index);
            if (entry == null) {
                misses.add(serialNumber);
                continue;
            }
            if (!serialNumber.equals(entry.serialNumber())) {
                IllegalStateException exception =
                        new IllegalStateException("Redis pricing cache value is invalid for its key");
                errorHandler.handleCacheGetError(exception, cache, serialNumber);
                batchDecodeErrors.increment();
                misses.add(serialNumber);
                continue;
            }
            found.put(serialNumber, entry);
        }
        batchHits.increment(serialNumbers.size() - misses.size());
        batchMisses.increment(misses.size());

        if (!misses.isEmpty()) {
            for (Pricing pricing : repository.findAllById(misses)) {
                PricingCacheEntry entry = PricingCacheEntry.from(pricing);
                found.put(entry.serialNumber(), entry);
            }
            if (!cacheReadFailed) {
                for (String serialNumber : misses) {
                    PricingCacheEntry entry = found.get(serialNumber);
                    if (entry == null) {
                        continue;
                    }
                    try {
                        cache.put(serialNumber, entry);
                    } catch (RuntimeException exception) {
                        errorHandler.handleCachePutError(exception, cache, serialNumber, entry);
                        batchPutErrors.increment();
                        break;
                    }
                }
            }
        }

        for (String serialNumber : misses) {
            if (!found.containsKey(serialNumber)) {
                throw new PricingNotFoundException(serialNumber);
            }
        }
        return List.copyOf(found.values());
    }

    private List<String> cacheKeys(List<String> serialNumbers, RedisCacheConfiguration configuration) {
        String prefix = configuration.usePrefix() ? configuration.getKeyPrefixFor(CACHE_NAME) : "";
        List<String> keys = new ArrayList<>(serialNumbers.size());
        for (String serialNumber : serialNumbers) {
            keys.add(prefix + serialNumber);
        }
        return keys;
    }
}
