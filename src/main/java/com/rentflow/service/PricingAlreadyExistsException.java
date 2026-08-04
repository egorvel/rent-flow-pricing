package com.rentflow.service;

public class PricingAlreadyExistsException extends RuntimeException {

    private final String serialNumber;

    public PricingAlreadyExistsException(String serialNumber) {
        super("Pricing already exists: " + serialNumber);
        this.serialNumber = serialNumber;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
