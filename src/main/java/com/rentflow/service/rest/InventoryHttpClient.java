package com.rentflow.service.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange("/api/v1/inventory")
public interface InventoryHttpClient {
    @GetExchange("/{serialNumber}")
    ResponseEntity<Void> getInventoryItem(@PathVariable("serialNumber") String serialNumber);
}
