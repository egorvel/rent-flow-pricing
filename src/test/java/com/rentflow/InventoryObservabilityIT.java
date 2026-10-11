package com.rentflow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

import com.rentflow.repository.PricingRepository;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(InventoryObservabilityIT.RecordingTraces.class)
class InventoryObservabilityIT extends PostgresIntegrationTest {
    private static final String LOGGER = "com.rentflow.service.rest.RestInventoryService";
    private static final String TRACE = "33333333333333333333333333333333";
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
    private static final Queue<Integer> RESPONSES = new ConcurrentLinkedQueue<>();
    private static final Queue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer INVENTORY = startInventory();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void dependencyProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add(
                "spring.http.serviceclient.inventory.base-url",
                () -> "http://127.0.0.1:" + INVENTORY.getAddress().getPort());
    }

    @Autowired
    private Environment environment;

    @Autowired
    private PricingRepository repository;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void reset() {
        repository.deleteAllInBatch();
        breakers.circuitBreaker("inventory").reset();
        RESPONSES.clear();
        REQUESTS.clear();
        flush();
        SPANS.reset();
    }

    @AfterAll
    static void stopInventory() {
        INVENTORY.stop(0);
        WORKERS.close();
    }

    @Test
    void correlatesBothRetryAttemptsAndIncludesTheirWaitInOneSuccessfulLookup() throws Exception {
        RESPONSES.addAll(List.of(503, 200));
        long countBefore = lookupCount("exists");
        try (EcsLogCapture logs = new EcsLogCapture(LOGGER)) {
            assertThat(create("INVENTORY-RETRY", "00-" + TRACE + "-4444444444444444-01")
                            .statusCode())
                    .isEqualTo(201);
            flush();
            SpanData logical = logicalSpan();
            assertThat(logical.getTraceId()).isEqualTo(TRACE);
            assertThat(logical.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(logical.getEvents())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getName()).isEqualTo("inventory.retry"));
            List<SpanData> attempts = SPANS.getFinishedSpanItems().stream()
                    .filter(span ->
                            span.getKind() == SpanKind.CLIENT && span.getName().startsWith("http"))
                    .toList();
            assertThat(attempts).hasSize(2).allSatisfy(span -> {
                assertThat(span.getTraceId()).isEqualTo(TRACE);
                assertThat(span.getParentSpanId()).isEqualTo(logical.getSpanId());
                assertThat(span.getStartEpochNanos()).isGreaterThanOrEqualTo(logical.getStartEpochNanos());
                assertThat(span.getEndEpochNanos()).isLessThanOrEqualTo(logical.getEndEpochNanos());
            });
            assertThat(REQUESTS).hasSize(2).allSatisfy(request -> {
                assertThat(request.traceparent()).startsWith("00-" + TRACE + "-");
                assertThat(request.baggage()).isNull();
            });
            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                JsonNode json = EcsLogCapture.encode(event, environment);
                assertThat(json.path("message").asText()).isEqualTo("Inventory lookup retry scheduled");
                assertThat(json.path("dependency").asText()).isEqualTo("inventory");
                assertThat(json.path("attempt").asInt()).isEqualTo(1);
                assertThat(json.path("failureCategory").asText()).isEqualTo("upstream_5xx");
                assertThat(json.path("traceId").asText()).isEqualTo(TRACE);
                assertThat(json.path("spanId").asText()).isEqualTo(logical.getSpanId());
                assertThat(json.path("error").path("stack_trace").asText()).contains("ServiceUnavailable");
                long waitMillis = json.path("waitMillis").asLong();
                assertThat(waitMillis).isBetween(400L, 600L);
                assertThat(logical.getEndEpochNanos() - logical.getStartEpochNanos())
                        .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(waitMillis));
            });
            assertThat(lookupCount("exists") - countBefore).isEqualTo(1);
            assertThat(breakers.circuitBreaker("inventory").getMetrics().getNumberOfSuccessfulCalls())
                    .isOne();
            assertThat(breakers.circuitBreaker("inventory").getMetrics().getNumberOfFailedCalls())
                    .isZero();
        }
        String scrape = get("local.management.port", "/actuator/prometheus").body();
        assertThat(scrape)
                .contains(
                        "pricing_inventory_lookup_seconds_bucket",
                        "http_client_requests_seconds_bucket",
                        "resilience4j_circuitbreaker_calls_seconds_count",
                        "resilience4j_retry_calls_total",
                        "name=\"inventory\"");
        assertThat(scrape).doesNotContain("INVENTORY-RETRY", TRACE);
    }

    @ParameterizedTest
    @CsvSource({
        "200,201,exists,false,1,0",
        "404,422,missing,false,1,0",
        "400,400,invalid_reference,false,1,0",
        "409,502,response_error,true,1,1",
        "204,502,response_error,true,1,1",
        "500,503,unavailable,true,1,1",
        "502,503,unavailable,true,2,2"
    })
    void preservesPublicMappingAndClassifiesTheLogicalOutcome(
            int upstream, int status, String outcome, boolean error, int attempts, int logCount) throws Exception {
        RESPONSES.addAll(List.of(upstream, upstream));
        long before = lookupCount(outcome);
        try (EcsLogCapture logs = new EcsLogCapture(LOGGER)) {
            assertThat(create("INVENTORY-MATRIX", null).statusCode()).isEqualTo(status);
            flush();
            SpanData logical = logicalSpan();
            assertThat(logical.getAttributes().toString()).contains("outcome=\"" + outcome + "\"");
            assertThat(logical.getStatus().getStatusCode()).isEqualTo(error ? StatusCode.ERROR : StatusCode.OK);
            assertThat(REQUESTS).hasSize(attempts).allSatisfy(request -> {
                assertThat(request.traceparent()).contains(logical.getTraceId());
                assertThat(request.baggage()).isNull();
            });
            assertThat(lookupCount(outcome) - before).isEqualTo(1);
            assertThat(logs.events()).hasSize(logCount);
            if (error) {
                assertThat(logs.events())
                        .filteredOn(event -> event.getFormattedMessage().equals("Inventory lookup failed"))
                        .singleElement()
                        .satisfies(event -> {
                            JsonNode json = EcsLogCapture.encode(event, environment);
                            assertThat(json.path("outcome").asText()).isEqualTo(outcome);
                            assertThat(json.path("traceId").asText()).isEqualTo(logical.getTraceId());
                            assertThat(json.path("spanId").asText()).isEqualTo(logical.getSpanId());
                            assertThat(json.path("error").path("stack_trace").asText())
                                    .isNotBlank();
                        });
            }
        }
        assertThat(repository.count()).isEqualTo(status == 201 ? 1 : 0);
        int calls = breakers.circuitBreaker("inventory").getMetrics().getNumberOfBufferedCalls();
        assertThat(calls).isEqualTo(upstream == 200 || upstream == 404 || upstream >= 500 ? 1 : 0);
    }

    @Test
    void reportsStateTransitionsAndShortCircuitsWithoutPerRejectionWarnings() throws Exception {
        CircuitBreaker breaker = breakers.circuitBreaker("inventory");
        try (EcsLogCapture logs = new EcsLogCapture(LOGGER)) {
            breaker.transitionToOpenState();
            for (int index = 0; index < 2; index++) {
                assertThat(create("INVENTORY-OPEN", null).statusCode()).isEqualTo(503);
            }
            flush();
            assertThat(SPANS.getFinishedSpanItems())
                    .filteredOn(span -> span.getName().equals("pricing.inventory.lookup"))
                    .hasSize(2)
                    .allSatisfy(span -> {
                        assertThat(span.getAttributes().toString()).contains("short_circuited");
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                    });
            assertThat(REQUESTS).isEmpty();
            assertThat(logs.events())
                    .singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
            breaker.transitionToClosedState();
            breaker.transitionToForcedOpenState();
            breaker.transitionToClosedState();
            breaker.transitionToOpenState();
            breaker.transitionToHalfOpenState();
            breaker.transitionToClosedState();
            assertThat(logs.events()).hasSize(7).allSatisfy(event -> {
                JsonNode json = EcsLogCapture.encode(event, environment);
                String state = json.path("newState").asText();
                assertThat(event.getLevel())
                        .isEqualTo(state.equals("OPEN") || state.equals("FORCED_OPEN") ? Level.WARN : Level.INFO);
                assertThat(json.path("previousState").asText()).isNotBlank();
                assertThat(json.path("traceId").asText()).isEmpty();
            });
        }
    }

    @Test
    void acceptsMalformedContextAndSkipsInventoryForLocalDuplicates() throws Exception {
        RESPONSES.add(200);
        assertThat(create("INVENTORY-DUPLICATE", "malformed-context").statusCode())
                .isEqualTo(201);
        flush();
        assertThat(logicalSpan().getTraceId()).hasSize(32);
        assertThat(REQUESTS)
                .singleElement()
                .satisfies(request -> assertThat(request.traceparent()).startsWith("00-"));
        SPANS.reset();
        REQUESTS.clear();
        try (EcsLogCapture logs = new EcsLogCapture(LOGGER)) {
            assertThat(create("INVENTORY-DUPLICATE", null).statusCode()).isEqualTo(409);
            flush();
            assertThat(SPANS.getFinishedSpanItems())
                    .noneSatisfy(span -> assertThat(span.getName()).isEqualTo("pricing.inventory.lookup"));
            assertThat(REQUESTS).isEmpty();
            assertThat(logs.events()).isEmpty();
        }
    }

    private long lookupCount(String outcome) {
        return metrics.find("pricing.inventory.lookup").tag("outcome", outcome).timers().stream()
                .mapToLong(io.micrometer.core.instrument.Timer::count)
                .sum();
    }

    private SpanData logicalSpan() {
        List<SpanData> logical = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("pricing.inventory.lookup"))
                .toList();
        assertThat(logical).hasSize(1);
        return logical.getFirst();
    }

    private HttpResponse<String> create(String serial, String traceparent) throws Exception {
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/pricing"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("baggage", "private=synthetic-baggage-value")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"serialNumber":"%s","price":124.57,"weekendRate":1,
                         "longRentalCondition":7,"longRentalDiscount":0.1,"deposit":345.67}
                        """.formatted(serial)));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String portProperty, String path) throws Exception {
        int port = environment.getRequiredProperty(portProperty, Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private void flush() {
        assertThat(tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess())
                .isTrue();
    }

    private static HttpServer startInventory() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/api/v1/inventory/", exchange -> {
                REQUESTS.add(new CapturedRequest(
                        exchange.getRequestHeaders().getFirst("traceparent"),
                        exchange.getRequestHeaders().getFirst("baggage")));
                Integer status = RESPONSES.poll();
                try {
                    exchange.sendResponseHeaders(status == null ? 500 : status, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start test Inventory server", exception);
        }
    }

    private record CapturedRequest(String traceparent, String baggage) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTraces {
        @Bean
        SpanExporter inventoryRecordingSpanExporter() {
            return SPANS;
        }
    }
}
