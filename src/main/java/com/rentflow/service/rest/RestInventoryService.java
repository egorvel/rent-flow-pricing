package com.rentflow.service.rest;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Service
public class RestInventoryService implements InventoryGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestInventoryService.class);

    private final InventoryHttpClient inventoryClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final ObservationRegistry observationRegistry;

    public RestInventoryService(
            InventoryHttpClient inventoryHttpClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ObservationRegistry observationRegistry) {
        this.inventoryClient = inventoryHttpClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        this.retry = retryRegistry.retry("inventory");
        this.observationRegistry = observationRegistry;
        retry.getEventPublisher().onRetry(event -> {
            Observation observation = observationRegistry.getCurrentObservation();
            if (observation != null) {
                observation.event(Observation.Event.of("inventory.retry"));
            }
            LOGGER.atWarn()
                    .addKeyValue("dependency", "inventory")
                    .addKeyValue("attempt", event.getNumberOfRetryAttempts())
                    .addKeyValue("waitMillis", event.getWaitInterval().toMillis())
                    .addKeyValue(
                            "failureCategory",
                            event.getLastThrowable() instanceof ResourceAccessException
                                    ? "resource_access"
                                    : "upstream_5xx")
                    .setCause(event.getLastThrowable())
                    .log("Inventory lookup retry scheduled");
        });
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            CircuitBreaker.State target = transition.getToState();
            Level level = target == CircuitBreaker.State.OPEN || target == CircuitBreaker.State.FORCED_OPEN
                    ? Level.WARN
                    : Level.INFO;
            LOGGER.atLevel(level)
                    .addKeyValue("dependency", "inventory")
                    .addKeyValue("previousState", transition.getFromState().name())
                    .addKeyValue("newState", target.name())
                    .log("Inventory circuit state changed");
        });
    }

    @Override
    public boolean exists(String serialNumber) {
        Observation observation = Observation.createNotStarted("pricing.inventory.lookup", observationRegistry)
                .lowCardinalityKeyValue("dependency", "inventory")
                .lowCardinalityKeyValue("operation", "exists")
                .start();
        String outcome = "error";
        try (Observation.Scope scope = observation.openScope()) {
            try {
                Supplier<Boolean> retriedCall = Retry.decorateSupplier(retry, () -> queryInventory(serialNumber));
                Supplier<Boolean> guardedCall = CircuitBreaker.decorateSupplier(circuitBreaker, retriedCall);
                boolean exists = guardedCall.get();
                outcome = exists ? "exists" : "missing";
                return exists;
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                    outcome = "invalid_reference";
                    throw new InvalidInventoryReferenceException(serialNumber, exception);
                }
                outcome = "response_error";
                failed(observation, outcome, exception);
                throw new InventoryServiceResponseException(serialNumber, exception);
            } catch (UnexpectedInventoryResponseException exception) {
                outcome = "response_error";
                failed(observation, outcome, exception);
                throw new InventoryServiceResponseException(serialNumber, exception);
            } catch (CallNotPermittedException exception) {
                outcome = "short_circuited";
                observation.error(exception);
                throw new InventoryServiceUnavailableException(serialNumber, exception);
            } catch (ResourceAccessException | HttpServerErrorException exception) {
                outcome = "unavailable";
                failed(observation, outcome, exception);
                throw new InventoryServiceUnavailableException(serialNumber, exception);
            } catch (RuntimeException | Error exception) {
                observation.error(exception);
                throw exception;
            }
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
        }
    }

    private void failed(Observation observation, String outcome, RuntimeException exception) {
        observation.error(exception);
        LOGGER.atWarn()
                .addKeyValue("dependency", "inventory")
                .addKeyValue("operation", "exists")
                .addKeyValue("outcome", outcome)
                .setCause(exception)
                .log("Inventory lookup failed");
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
