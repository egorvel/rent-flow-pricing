package com.rentflow.service;

import java.util.Arrays;

public enum PricingSortField {
    SERIAL_NUMBER("serialNumber"),
    PRICE("price"),
    WEEKEND_RATE("weekendRate"),
    LONG_RENTAL_CONDITION("longRentalCondition"),
    LONG_RENTAL_DISCOUNT("longRentalDiscount"),
    DEPOSIT("deposit");

    private final String property;

    PricingSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    public static PricingSortField fromApiName(String apiName) {
        return Arrays.stream(values())
                .filter(field -> field.property.equals(apiName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported pricing sort field"));
    }
}
