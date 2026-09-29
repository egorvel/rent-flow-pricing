package com.rentflow.service;

import java.util.Objects;

public record PricingChangedEvent(String serialNumber) {

    public PricingChangedEvent {
        Objects.requireNonNull(serialNumber, "serialNumber must not be null");
    }
}
