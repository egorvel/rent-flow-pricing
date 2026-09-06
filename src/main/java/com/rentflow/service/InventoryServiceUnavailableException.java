package com.rentflow.service;

public class InventoryServiceUnavailableException extends RuntimeException {

    public InventoryServiceUnavailableException(String serialNumber, Throwable cause) {
        super("Inventory is unavailable while checking serial number: " + serialNumber, cause);
    }
}
