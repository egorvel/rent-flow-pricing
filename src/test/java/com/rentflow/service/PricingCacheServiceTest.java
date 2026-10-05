package com.rentflow.service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingCacheServiceTest {

    @Mock
    private PricingRepository repository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private RedisTemplate<String, PricingCacheEntry> redisTemplate;

    @Mock
    private ValueOperations<String, PricingCacheEntry> valueOperations;

    @Mock
    private CacheErrorHandler errorHandler;

    @Mock
    private RedisCache cache;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void loadsAndMapsAllPricingValuesOnAMiss() {
        Pricing pricing = pricing("DRILL-001");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        PricingCacheService service = service();

        PricingCacheEntry entry = service.get("DRILL-001");

        assertThat(entry.serialNumber()).isEqualTo("DRILL-001");
        assertThat(entry.price()).isEqualByComparingTo("125.50");
        assertThat(entry.weekendRate()).isEqualByComparingTo("1.2500");
        assertThat(entry.longRentalCondition()).isEqualTo(7);
        assertThat(entry.longRentalDiscount()).isEqualByComparingTo("0.1000");
        assertThat(entry.deposit()).isEqualByComparingTo("300.00");
        verify(repository).findById("DRILL-001");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void neverTurnsMissingPricingIntoAReturnValue() {
        when(repository.findById("MISSING")).thenReturn(Optional.empty());
        PricingCacheService service = service();

        assertThatThrownBy(() -> service.get("MISSING"))
                .isInstanceOf(PricingNotFoundException.class)
                .hasMessage("Pricing not found: MISSING");
        assertThatThrownBy(() -> service.get("MISSING")).isInstanceOf(PricingNotFoundException.class);

        verify(repository, times(2)).findById("MISSING");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void declaresTheSeparateAsynchronousCacheAndReadOnlyTransactionBoundary() throws Exception {
        Method method = PricingCacheService.class.getMethod("get", String.class);
        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.cacheNames()).containsExactly(PricingCacheService.CACHE_NAME);
        assertThat(cacheable.key()).isEqualTo("#serialNumber");
        assertThat(cacheable.sync()).isFalse();
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void bulkReadsMissesOnceAndWarmsEachFoundEntry() {
        RedisCacheConfiguration configuration = configureCache();
        when(valueOperations.multiGet(cacheKeys("DRILL-001", "SAW-002")))
                .thenReturn(java.util.Arrays.asList(null, null));
        when(repository.findAllById(List.of("DRILL-001", "SAW-002")))
                .thenReturn(List.of(pricing("SAW-002"), pricing("DRILL-001")));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001", "SAW-002"));

        assertThat(entries)
                .extracting(PricingCacheEntry::serialNumber)
                .containsExactlyInAnyOrder("DRILL-001", "SAW-002");
        verify(valueOperations, times(1)).multiGet(cacheKeys("DRILL-001", "SAW-002"));
        verify(repository).findAllById(List.of("DRILL-001", "SAW-002"));
        verify(repository, never()).findById(any());
        verify(cache).put("DRILL-001", PricingCacheEntry.from(pricing("DRILL-001")));
        verify(cache).put("SAW-002", PricingCacheEntry.from(pricing("SAW-002")));
        assertThat(configuration.getKeyPrefixFor(PricingCacheService.CACHE_NAME))
                .isEqualTo("rentflow:pricing:v1:pricingBySerial::");
        assertThat(meterRegistry
                        .get("pricing.cache.batch.entries")
                        .tag("outcome", "miss")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.get("pricing.cache.batch.size").summary().totalAmount())
                .isEqualTo(2.0);
    }

    @Test
    void bulkUsesEveryValidHitWithoutCallingPostgres() {
        RedisCacheConfiguration configuration = configureCache();
        PricingCacheEntry drill = PricingCacheEntry.from(pricing("DRILL-001"));
        PricingCacheEntry saw = PricingCacheEntry.from(pricing("SAW-002"));
        when(valueOperations.multiGet(cacheKeys("DRILL-001", "SAW-002"))).thenReturn(List.of(drill, saw));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001", "SAW-002"));

        assertThat(entries).containsExactlyInAnyOrder(drill, saw);
        verifyNoInteractions(repository);
        verify(cache, never()).put(any(), any());
        assertThat(meterRegistry
                        .get("pricing.cache.batch.entries")
                        .tag("outcome", "hit")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void deserializationFailureFallsBackForTheWholeBatchAndSkipsWrites() {
        configureCache();
        SerializationException failure = new SerializationException("invalid JSON");
        when(valueOperations.multiGet(cacheKeys("DRILL-001", "SAW-002"))).thenThrow(failure);
        when(repository.findAllById(List.of("DRILL-001", "SAW-002")))
                .thenReturn(List.of(pricing("DRILL-001"), pricing("SAW-002")));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001", "SAW-002"));

        assertThat(entries)
                .extracting(PricingCacheEntry::serialNumber)
                .containsExactlyInAnyOrder("DRILL-001", "SAW-002");
        verify(repository).findAllById(List.of("DRILL-001", "SAW-002"));
        verify(cache, never()).put(any(), any());
        verify(errorHandler).handleCacheGetError(failure, cache, List.of("DRILL-001", "SAW-002"));
        assertThat(meterRegistry
                        .get("pricing.cache.batch.errors")
                        .tag("operation", "decode")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void cachedValueForAnotherSerialBecomesAMiss() {
        configureCache();
        PricingCacheEntry wrongEntry = PricingCacheEntry.from(pricing("OTHER"));
        when(valueOperations.multiGet(cacheKeys("DRILL-001"))).thenReturn(List.of(wrongEntry));
        when(repository.findAllById(List.of("DRILL-001"))).thenReturn(List.of(pricing("DRILL-001")));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001"));

        assertThat(entries).extracting(PricingCacheEntry::serialNumber).containsExactly("DRILL-001");
        verify(repository).findAllById(List.of("DRILL-001"));
        verify(errorHandler)
                .handleCacheGetError(
                        any(RuntimeException.class),
                        org.mockito.ArgumentMatchers.same(cache),
                        org.mockito.ArgumentMatchers.eq("DRILL-001"));
    }

    @Test
    void failedBulkReadFallsBackOnceAndSkipsAllCacheWrites() {
        configureCache();
        IllegalStateException failure = new IllegalStateException("redis unavailable");
        when(valueOperations.multiGet(cacheKeys("DRILL-001", "SAW-002"))).thenThrow(failure);
        when(repository.findAllById(List.of("DRILL-001", "SAW-002")))
                .thenReturn(List.of(pricing("DRILL-001"), pricing("SAW-002")));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001", "SAW-002"));

        assertThat(entries).hasSize(2);
        verify(repository).findAllById(List.of("DRILL-001", "SAW-002"));
        verify(cache, never()).put(any(), any());
        verify(errorHandler).handleCacheGetError(failure, cache, List.of("DRILL-001", "SAW-002"));
        assertThat(meterRegistry
                        .get("pricing.cache.batch.errors")
                        .tag("operation", "read")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void firstFailedPutSkipsRemainingWritesWithoutChangingTheResult() {
        configureCache();
        when(valueOperations.multiGet(cacheKeys("DRILL-001", "SAW-002")))
                .thenReturn(java.util.Arrays.asList(null, null));
        when(repository.findAllById(List.of("DRILL-001", "SAW-002")))
                .thenReturn(List.of(pricing("DRILL-001"), pricing("SAW-002")));
        IllegalStateException failure = new IllegalStateException("redis unavailable");
        doThrow(failure).when(cache).put("DRILL-001", PricingCacheEntry.from(pricing("DRILL-001")));
        PricingCacheService service = service();

        List<PricingCacheEntry> entries = service.getBatch(List.of("DRILL-001", "SAW-002"));

        assertThat(entries).hasSize(2);
        verify(cache, never()).put("SAW-002", PricingCacheEntry.from(pricing("SAW-002")));
        verify(errorHandler)
                .handleCachePutError(failure, cache, "DRILL-001", PricingCacheEntry.from(pricing("DRILL-001")));
        assertThat(meterRegistry
                        .get("pricing.cache.batch.errors")
                        .tag("operation", "put")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void missingPricingWarmsEveryFoundEntryBeforeReturningNotFound() {
        configureCache();
        when(valueOperations.multiGet(cacheKeys("FIRST", "MISSING", "LATER")))
                .thenReturn(java.util.Arrays.asList(null, null, null));
        when(repository.findAllById(List.of("FIRST", "MISSING", "LATER")))
                .thenReturn(List.of(pricing("FIRST"), pricing("LATER")));
        PricingCacheService service = service();

        assertThatThrownBy(() -> service.getBatch(List.of("FIRST", "MISSING", "LATER")))
                .isInstanceOf(PricingNotFoundException.class);

        verify(cache).put("FIRST", PricingCacheEntry.from(pricing("FIRST")));
        verify(cache).put("LATER", PricingCacheEntry.from(pricing("LATER")));
        verify(cache, never()).put(org.mockito.ArgumentMatchers.eq("MISSING"), any());
    }

    private PricingCacheService service() {
        return new PricingCacheService(repository, cacheManager, redisTemplate, errorHandler, meterRegistry);
    }

    private RedisCacheConfiguration configureCache() {
        RedisCacheConfiguration configuration =
                RedisCacheConfiguration.defaultCacheConfig().prefixCacheNameWith("rentflow:pricing:v1:");
        when(cacheManager.getCache(PricingCacheService.CACHE_NAME)).thenReturn(cache);
        when(cache.getCacheConfiguration()).thenReturn(configuration);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return configuration;
    }

    private List<String> cacheKeys(String... serialNumbers) {
        List<String> keys = new ArrayList<>(serialNumbers.length);
        for (String serialNumber : serialNumbers) {
            keys.add("rentflow:pricing:v1:pricingBySerial::" + serialNumber);
        }
        return keys;
    }

    private Pricing pricing(String serialNumber) {
        return new Pricing(
                serialNumber,
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }
}
