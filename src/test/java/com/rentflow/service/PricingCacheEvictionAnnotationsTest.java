package com.rentflow.service;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;

import com.rentflow.model.Pricing;

import static org.assertj.core.api.Assertions.assertThat;

class PricingCacheEvictionAnnotationsTest {

    @Test
    void createEvictsOnlyItsSerialAfterSuccess() throws Exception {
        assertEviction(PricingService.class.getMethod("create", Pricing.class), "#pricing.serialNumber");
    }

    @Test
    void replaceEvictsOnlyItsSerialAfterSuccess() throws Exception {
        assertEviction(PricingService.class.getMethod("replace", Pricing.class), "#replacement.serialNumber");
    }

    @Test
    void deleteEvictsOnlyItsSerialAfterSuccess() throws Exception {
        assertEviction(PricingService.class.getMethod("delete", String.class), "#serialNumber");
    }

    private void assertEviction(Method method, String key) {
        CacheEvict cacheEvict = method.getAnnotation(CacheEvict.class);

        assertThat(cacheEvict).isNotNull();
        assertThat(cacheEvict.cacheNames()).containsExactly(PricingCacheService.CACHE_NAME);
        assertThat(cacheEvict.key()).isEqualTo(key);
        assertThat(cacheEvict.allEntries()).isFalse();
        assertThat(cacheEvict.beforeInvocation()).isFalse();
    }
}
