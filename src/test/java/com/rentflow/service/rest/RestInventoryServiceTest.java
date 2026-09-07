package com.rentflow.service.rest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.rentflow.service.InvalidInventoryReferenceException;
import com.rentflow.service.InventoryServiceResponseException;
import com.rentflow.service.InventoryServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestInventoryServiceTest {

    @Mock
    private InventoryHttpClient inventoryClient;

    private CircuitBreaker circuitBreaker;
    private RestInventoryService service;

    @BeforeEach
    void configureResilience() {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class, UnexpectedInventoryResponseException.class)
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ZERO)
                .retryOnException(new InventoryRetryableExceptionPredicate())
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        service = new RestInventoryService(inventoryClient, circuitBreakerRegistry, retryRegistry);
    }

    @Test
    void reportsThatAnInventoryItemExistsForAnExactOkResponse() {
        when(inventoryClient.getInventoryItem("DRILL-001"))
                .thenReturn(ResponseEntity.ok().build());

        assertThat(service.exists("DRILL-001")).isTrue();

        verify(inventoryClient).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isOne();
    }

    @Test
    void convertsNotFoundToANormalResultThatDoesNotFailTheCircuitBreaker() {
        when(inventoryClient.getInventoryItem("MISSING")).thenThrow(clientError(HttpStatus.NOT_FOUND));

        assertThat(service.exists("MISSING")).isFalse();

        verify(inventoryClient).getInventoryItem("MISSING");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isOne();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void retriesANetworkFailureOnceAndRecordsOneSuccessfulLogicalCall() {
        when(inventoryClient.getInventoryItem("DRILL-001"))
                .thenThrow(new ResourceAccessException("connection refused"))
                .thenReturn(ResponseEntity.ok().build());

        assertThat(service.exists("DRILL-001")).isTrue();

        verify(inventoryClient, org.mockito.Mockito.times(2)).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isOne();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void retriesBadGatewayOnceAndRecordsOneFailedLogicalCallWhenBothAttemptsFail() {
        when(inventoryClient.getInventoryItem("DRILL-001"))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY))
                .thenThrow(serverError(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InventoryServiceUnavailableException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);

        verify(inventoryClient, org.mockito.Mockito.times(2)).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isOne();
    }

    @Test
    void doesNotRetryOtherServerErrorsButStillRecordsTheLogicalCallAsFailed() {
        when(inventoryClient.getInventoryItem("DRILL-001")).thenThrow(serverError(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InventoryServiceUnavailableException.class)
                .hasCauseInstanceOf(HttpServerErrorException.class);

        verify(inventoryClient).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isOne();
    }

    @Test
    void mapsBadRequestWithoutRetryingOrRecordingABreakerFailure() {
        HttpClientErrorException failure = clientError(HttpStatus.BAD_REQUEST);
        when(inventoryClient.getInventoryItem("DRILL-001")).thenThrow(failure);

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InvalidInventoryReferenceException.class)
                .hasCause(failure);

        verify(inventoryClient).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void mapsOtherClientErrorsToBadGatewayWithoutRetryingOrFailingTheBreaker() {
        HttpClientErrorException failure = clientError(HttpStatus.CONFLICT);
        when(inventoryClient.getInventoryItem("DRILL-001")).thenThrow(failure);

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InventoryServiceResponseException.class)
                .hasCause(failure);

        verify(inventoryClient).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void mapsAnUnexpectedNonErrorStatusToBadGatewayWithoutFailingTheBreaker() {
        when(inventoryClient.getInventoryItem("DRILL-001"))
                .thenReturn(ResponseEntity.status(HttpStatus.NO_CONTENT).build());

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InventoryServiceResponseException.class)
                .hasCauseInstanceOf(UnexpectedInventoryResponseException.class);

        verify(inventoryClient).getInventoryItem("DRILL-001");
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void rejectsCallsImmediatelyWhenTheCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> service.exists("DRILL-001"))
                .isInstanceOf(InventoryServiceUnavailableException.class)
                .hasCauseInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        verify(inventoryClient, never()).getInventoryItem("DRILL-001");
    }

    private HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
