package com.rentflow.controller;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerMapping;

import com.rentflow.dto.ProblemResponse;
import com.rentflow.support.EcsLogCapture;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler =
            new ApiExceptionHandler(io.micrometer.observation.ObservationRegistry.NOOP);

    @Test
    void sanitizesUnexpectedFailuresWithoutStartingASpringContext() {
        String sensitiveText = "IllegalStateException stack trace SELECT password jdbc:postgresql://db/rentflow "
                + "pricing-secret environment-value request-body-value";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pricing");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer pricing-secret");
        request.setContent("request-body-value".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ProblemResponse> response =
                handler.handleUnexpected(new IllegalStateException(sensitiveText), new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:internal-error",
                        "Internal server error",
                        500,
                        "An unexpected error occurred.",
                        "/api/v1/pricing",
                        "INTERNAL_ERROR",
                        java.util.List.of()));
        assertThat(response.getBody().toString())
                .doesNotContain(
                        "IllegalStateException",
                        "stack trace",
                        "SELECT",
                        "jdbc:postgresql",
                        "password",
                        "pricing-secret",
                        "environment-value",
                        "request-body-value");
    }

    @Test
    void convertsOtherMvcClientErrorsWithoutLeakingFrameworkDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pricing");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalArgumentException("framework detail"),
                null,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .isEqualTo(new ProblemResponse(
                        "urn:rentflow:problem:http-error",
                        "Request failed",
                        400,
                        "The request could not be processed.",
                        "/api/v1/pricing",
                        "HTTP_ERROR",
                        java.util.List.of()));
    }

    @Test
    void logsUnexpectedFailuresWithTheCompleteStackTrace(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pricing/DRILL-001");

        handler.handleUnexpected(new IllegalStateException("boom"), new ServletWebRequest(request));

        assertThat(output)
                .contains("Unexpected failure handling request")
                .contains("java.lang.IllegalStateException: boom")
                .contains(
                        "at com.rentflow.controller.ApiExceptionHandlerTest.logsUnexpectedFailuresWithTheCompleteStackTrace");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsAndLogsUnexpectedFailuresFromBothHandlerPaths(boolean frameworkFailure) {
        ObservationRegistry registry = mock(ObservationRegistry.class);
        Observation observation = mock(Observation.class);
        when(registry.getCurrentObservation()).thenReturn(observation);
        ApiExceptionHandler observedHandler = new ApiExceptionHandler(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pricing/DRILL-001");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/pricing/{serialNumber}");
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        IllegalStateException failure = new IllegalStateException("boom", new IllegalArgumentException("cause detail"));

        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            ResponseEntity<?> response = frameworkFailure
                    ? observedHandler.handleExceptionInternal(
                            failure,
                            "internal detail",
                            HttpHeaders.EMPTY,
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            new ServletWebRequest(request))
                    : observedHandler.handleUnexpected(failure, new ServletWebRequest(request));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody())
                    .isEqualTo(new ProblemResponse(
                            "urn:rentflow:problem:internal-error",
                            "Internal server error",
                            500,
                            "An unexpected error occurred.",
                            "/api/v1/pricing/DRILL-001",
                            "INTERNAL_ERROR",
                            java.util.List.of()));
            verify(observation).error(failure);
            assertThat(context.getError()).isSameAs(failure);
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                JsonNode json = EcsLogCapture.encode(event, new MockEnvironment());
                assertThat(json.path("message").asText()).isEqualTo("Unexpected failure handling request");
                assertThat(json.path("method").asText()).isEqualTo("GET");
                assertThat(json.path("route").asText()).isEqualTo("/api/v1/pricing/{serialNumber}");
                assertThat(json.path("path").asText()).isEqualTo("/api/v1/pricing/DRILL-001");
                assertThat(json.path("exceptionType").asText())
                        .isEqualTo(failure.getClass().getName());
                assertThat(json.path("error").path("stack_trace").asText())
                        .contains("boom", "cause detail", "recordsAndLogsUnexpectedFailuresFromBothHandlerPaths");
            });
        }
    }

    @Test
    void usesUnmatchedRouteWhenNoHandlerPatternIsAvailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pricing");
        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            handler.handleUnexpected(new IllegalStateException("boom"), new ServletWebRequest(request));
            assertThat(logs.events())
                    .singleElement()
                    .satisfies(event -> assertThat(EcsLogCapture.encode(event, new MockEnvironment())
                                    .path("route")
                                    .asText())
                            .isEqualTo("unmatched"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void expectedClientErrorsDoNotRecordOrLogAnUnexpectedFailure(int status) {
        ObservationRegistry registry = mock(ObservationRegistry.class);
        Observation observation = mock(Observation.class);
        when(registry.getCurrentObservation()).thenReturn(observation);
        ApiExceptionHandler observedHandler = new ApiExceptionHandler(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pricing");
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        try (EcsLogCapture logs = new EcsLogCapture(ApiExceptionHandler.class.getName())) {
            ResponseEntity<Object> response = observedHandler.handleExceptionInternal(
                    new IllegalArgumentException("expected rejection"),
                    null,
                    HttpHeaders.EMPTY,
                    HttpStatus.valueOf(status),
                    new ServletWebRequest(request));
            assertThat(response.getStatusCode().value()).isEqualTo(status);
            verifyNoInteractions(observation);
            assertThat(context.getError()).isNull();
            assertThat(logs.events()).isEmpty();
        }
    }
}
