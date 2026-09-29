package com.rentflow.model;

import java.math.BigDecimal;
import java.util.Objects;

public record PricingCacheEntry(
        String serialNumber,
        BigDecimal price,
        BigDecimal weekendRate,
        Integer longRentalCondition,
        BigDecimal longRentalDiscount,
        BigDecimal deposit) {

    public PricingCacheEntry {
        Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(weekendRate, "weekendRate must not be null");
        Objects.requireNonNull(longRentalCondition, "longRentalCondition must not be null");
        Objects.requireNonNull(longRentalDiscount, "longRentalDiscount must not be null");
        Objects.requireNonNull(deposit, "deposit must not be null");
    }

    public static PricingCacheEntry from(Pricing pricing) {
        Objects.requireNonNull(pricing, "pricing must not be null");
        return new PricingCacheEntry(
                pricing.getSerialNumber(),
                pricing.getPrice(),
                pricing.getWeekendRate(),
                pricing.getLongRentalCondition(),
                pricing.getLongRentalDiscount(),
                pricing.getDeposit());
    }
}
