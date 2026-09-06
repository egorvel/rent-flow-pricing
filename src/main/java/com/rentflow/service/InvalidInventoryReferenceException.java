package com.rentflow.service;

public class InvalidInventoryReferenceException extends RuntimeException {

    private final String serialNumber;

    public InvalidInventoryReferenceException(String serialNumber, Throwable cause) {
        super("Inventory rejected serial number as invalid: " + serialNumber, cause);
        this.serialNumber = serialNumber;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
