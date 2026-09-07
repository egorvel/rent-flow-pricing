package com.rentflow.service.rest;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryRetryableExceptionPredicateTest {

    private final InventoryRetryableExceptionPredicate predicate = new InventoryRetryableExceptionPredicate();

    @Test
    void retriesResourceAccessFailures() {
        assertThat(predicate.test(new ResourceAccessException("connection refused")))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    void retriesOnlyTheSelectedServerStatuses(int status) {
        assertThat(predicate.test(serverError(HttpStatus.valueOf(status)))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501, 505})
    void doesNotRetryOtherServerStatuses(int status) {
        assertThat(predicate.test(serverError(HttpStatus.valueOf(status)))).isFalse();
    }

    @Test
    void doesNotRetryClientOrProgrammingFailures() {
        HttpClientErrorException clientError = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);

        assertThat(predicate.test(clientError)).isFalse();
        assertThat(predicate.test(new IllegalStateException("bug"))).isFalse();
    }

    private HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(
                status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
