package com.rentflow.service;

public class PricingNotFoundException extends RuntimeException {

    private final String serialNumber;

    public PricingNotFoundException(String serialNumber) {
        super("Pricing not found: " + serialNumber);
        this.serialNumber = serialNumber;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
