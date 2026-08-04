package com.rentflow.model;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PricingTest {

    @Test
    void keepsAssignedSerialNumberWhenDetailsAreReplaced() {
        Pricing pricing = pricing("DRILL-001", "125.50");

        pricing.replaceDetails(
                new BigDecimal("150.00"),
                new BigDecimal("1.5000"),
                14,
                new BigDecimal("0.2500"),
                new BigDecimal("450.00"));

        assertThat(pricing.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(pricing.getPrice()).isEqualByComparingTo("150.00");
        assertThat(pricing.getWeekendRate()).isEqualByComparingTo("1.5000");
        assertThat(pricing.getLongRentalCondition()).isEqualTo(14);
        assertThat(pricing.getLongRentalDiscount()).isEqualByComparingTo("0.2500");
        assertThat(pricing.getDeposit()).isEqualByComparingTo("450.00");
    }

    @Test
    void exposesNoSerialNumberMutator() {
        assertThat(Pricing.class.getMethods()).extracting(Method::getName).doesNotContain("setSerialNumber");
    }

    @Test
    void usesAssignedSerialNumberForEquality() {
        Pricing first = pricing("DRILL-001", "125.50");
        Pricing sameIdentity = pricing("DRILL-001", "999.99");
        Pricing differentIdentity = pricing("drill-001", "125.50");

        assertThat(first).isEqualTo(sameIdentity).hasSameHashCodeAs(sameIdentity);
        assertThat(first).isNotEqualTo(differentIdentity);
    }

    @Test
    void requiresEveryConstructorValue() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Pricing(
                        null, new BigDecimal("1.00"), new BigDecimal("1.0000"), 1, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() ->
                        new Pricing("DRILL-001", null, new BigDecimal("1.0000"), 1, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() ->
                        new Pricing("DRILL-001", new BigDecimal("1.00"), null, 1, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> new Pricing(
                        "DRILL-001",
                        new BigDecimal("1.00"),
                        new BigDecimal("1.0000"),
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> new Pricing(
                        "DRILL-001", new BigDecimal("1.00"), new BigDecimal("1.0000"), 1, null, BigDecimal.ZERO));
        assertThatNullPointerException()
                .isThrownBy(() -> new Pricing(
                        "DRILL-001", new BigDecimal("1.00"), new BigDecimal("1.0000"), 1, BigDecimal.ZERO, null));
    }

    private Pricing pricing(String serialNumber, String price) {
        return new Pricing(
                serialNumber,
                new BigDecimal(price),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }
}
