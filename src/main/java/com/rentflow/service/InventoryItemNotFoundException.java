package com.rentflow.service;

public class InventoryItemNotFoundException extends RuntimeException {

    private final String serialNumber;

    public InventoryItemNotFoundException(String serialNumber) {
        super("Inventory item not found: " + serialNumber);
        this.serialNumber = serialNumber;
    }

    public String getSerialNumber() {
        return serialNumber;
    }
}
