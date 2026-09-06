package com.rentflow.service.rest;

import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.rentflow.service.InvalidInventoryReferenceException;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.InventoryServiceResponseException;
import com.rentflow.service.InventoryServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

@Service
public class RestInventoryService implements InventoryGateway {

    private final InventoryHttpClient inventoryClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public RestInventoryService(
            InventoryHttpClient inventoryHttpClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        this.inventoryClient = inventoryHttpClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        this.retry = retryRegistry.retry("inventory");
    }

    @Override
    public boolean exists(String serialNumber) {
        Supplier<Boolean> retriedCall = Retry.decorateSupplier(retry, () -> queryInventory(serialNumber));
        Supplier<Boolean> guardedCall = CircuitBreaker.decorateSupplier(circuitBreaker, retriedCall);

        try {
            return guardedCall.get();
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                throw new InvalidInventoryReferenceException(serialNumber, exception);
            }
            throw new InventoryServiceResponseException(serialNumber, exception);
        } catch (UnexpectedInventoryResponseException exception) {
            throw new InventoryServiceResponseException(serialNumber, exception);
        } catch (CallNotPermittedException | ResourceAccessException | HttpServerErrorException exception) {
            throw new InventoryServiceUnavailableException(serialNumber, exception);
        }
    }

    private boolean queryInventory(String serialNumber) {
        try {
            ResponseEntity<Void> response = inventoryClient.getInventoryItem(serialNumber);
            if (response.getStatusCode().value() == HttpStatus.OK.value()) {
                return true;
            }
            if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw new UnexpectedInventoryResponseException(response.getStatusCode());
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return false;
            }
            throw exception;
        }
    }
}
