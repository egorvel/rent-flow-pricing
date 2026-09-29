package com.rentflow.model;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PricingCacheEntryTest {

    @Test
    void preservesAllPricingValuesFromTheEntity() {
        Pricing pricing = new Pricing(
                "DRILL-001",
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));

        PricingCacheEntry entry = PricingCacheEntry.from(pricing);

        assertThat(entry.serialNumber()).isEqualTo("DRILL-001");
        assertThat(entry.price()).isEqualByComparingTo("125.5");
        assertThat(entry.weekendRate()).isEqualByComparingTo("1.25");
        assertThat(entry.longRentalCondition()).isEqualTo(7);
        assertThat(entry.longRentalDiscount()).isEqualByComparingTo("0.1");
        assertThat(entry.deposit()).isEqualByComparingTo("300");
    }

    @Test
    void rejectsEveryNullComponent() {
        BigDecimal one = BigDecimal.ONE;

        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry(null, one, one, 1, one, one));
        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry("DRILL-001", null, one, 1, one, one));
        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry("DRILL-001", one, null, 1, one, one));
        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry("DRILL-001", one, one, null, one, one));
        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry("DRILL-001", one, one, 1, null, one));
        assertThatNullPointerException().isThrownBy(() -> new PricingCacheEntry("DRILL-001", one, one, 1, one, null));
        assertThatNullPointerException().isThrownBy(() -> PricingCacheEntry.from(null));
    }
}
