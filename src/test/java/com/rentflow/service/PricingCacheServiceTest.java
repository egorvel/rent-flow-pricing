package com.rentflow.service;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingCacheServiceTest {

    @Mock
    private PricingRepository repository;

    @Test
    void loadsAndMapsAllPricingValuesOnAMiss() {
        Pricing pricing = pricing("DRILL-001");
        when(repository.findById("DRILL-001")).thenReturn(Optional.of(pricing));
        PricingCacheService service = new PricingCacheService(repository);

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
        PricingCacheService service = new PricingCacheService(repository);

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
