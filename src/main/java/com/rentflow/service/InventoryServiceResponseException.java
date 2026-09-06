package com.rentflow.service;

public class InventoryServiceResponseException extends RuntimeException {

    public InventoryServiceResponseException(String serialNumber, Throwable cause) {
        super("Inventory returned an unexpected response for serial number: " + serialNumber, cause);
    }
}
