package com.rentflow;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.service.InventoryGateway;
import com.rentflow.support.EcsLogCapture;
import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(ObservabilityIT.RecordingTraces.class)
class ObservabilityIT extends PostgresIntegrationTest {
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoSpyBean
    private PricingRepository repository;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void resetSpans() {
        flushSpans();
        SPANS.reset();
    }

    @Test
    void isolatesManagementAndPreservesApplicationProbesAndExistingMeters() throws Exception {
        assertThat(get("local.server.port", "/actuator/prometheus").statusCode())
                .isEqualTo(404);
        assertThat(get("local.server.port", "/actuator/health").statusCode()).isEqualTo(404);
        for (String path : new String[] {"/actuator/env", "/actuator/metrics", "/actuator/configprops"}) {
            assertThat(get("local.management.port", path).statusCode()).isEqualTo(404);
        }
        assertThat(get("local.server.port", "/livez").body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("local.server.port", "/readyz").body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("local.server.port", "/api/v1/pricing").statusCode()).isEqualTo(200);
        assertThat(get("local.management.port", "/actuator/prometheus").body())
                .contains(
                        "jvm_memory_used_bytes",
                        "hikaricp_connections",
                        "http_server_requests_seconds_bucket",
                        "service=\"rent-flow-pricing\"",
                        "environment=\"local\"",
                        "pricing_cache_errors_total",
                        "pricing_cache_batch_entries_total",
                        "resilience4j_circuitbreaker_state");
        assertThat(environment.getProperty("management.tracing.export.otlp.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.tracing.baggage.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }

    @Test
    void excludesProbeAndManagementObservationsButKeepsUnmatchedBusinessRequests() throws Exception {
        for (String path : new String[] {"/livez", "/readyz"}) {
            get("local.server.port", path);
        }
        get("local.management.port", "/actuator/prometheus");
        get("local.management.port", "/actuator/health/readiness");
        get("local.server.port", "/actuator-business-request");
        flushSpans();
        assertThat(SPANS.getFinishedSpanItems())
                .filteredOn(span -> span.getKind() == io.opentelemetry.api.trace.SpanKind.SERVER)
                .singleElement()
                .satisfies(span -> assertThat(span.getAttributes().toString()).contains("/actuator-business-request"));
        assertThat(metrics.find("http.server.requests").tag("uri", "/readyz").meters())
                .isEmpty();
        assertThat(metrics.find("http.server.requests").tag("uri", "/livez").meters())
                .isEmpty();
    }

    @Test
    void correlatesJdbcAndRedisWithoutCapturingParametersArgumentsOrHttpInputs() throws Exception {
        String serial = "TRACE-REDIS";
        repository.saveAndFlush(new Pricing(
                serial, new BigDecimal("124.57"), BigDecimal.ONE, 7, new BigDecimal("0.1"), new BigDecimal("345.67")));
        redis.delete("rentflow:pricing:v1:pricingBySerial::" + serial);
        flushSpans();
        SPANS.reset();
        String traceId = "11111111111111111111111111111111";
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/pricing/"
                            + serial + "/cached?private=synthetic-query-value"))
                    .header("traceparent", "00-" + traceId + "-2222222222222222-01")
                    .header("Authorization", "Bearer synthetic-header-value")
                    .header("Cookie", "synthetic-cookie-value")
                    .header("baggage", "private=synthetic-baggage-value")
                    .GET()
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
        }
        flushSpans();
        List<SpanData> spans = SPANS.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).contains("connection"));
        assertThat(spans)
                .anySatisfy(span ->
                        assertThat(span.getAttributes().toString()).contains("jdbc.query", "pricing_entries", "?"));
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).isEqualTo("get"));
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).isEqualTo("set"));
        assertThat(spans).allSatisfy(span -> {
            assertThat(span.getAttributes().toString() + span.getEvents())
                    .doesNotContain(
                            "124.57",
                            "345.67",
                            "jdbc.params",
                            "synthetic-query-value",
                            "synthetic-header-value",
                            "synthetic-cookie-value",
                            "synthetic-baggage-value",
                            "rentflow:pricing:v1:");
            assertThat(span.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("rent-flow-pricing");
        });
        assertThat(get("local.management.port", "/actuator/prometheus").body()).doesNotContain(serial);
        assertThat(metrics.get("http.server.requests")
                        .tag("uri", "/api/v1/pricing/{serialNumber}/cached")
                        .timer()
                        .count())
                .isPositive();
        SPANS.reset();
        assertThat(jdbc.queryForObject("SELECT ?::text", String.class, "synthetic-bound-value"))
                .isEqualTo("synthetic-bound-value");
        flushSpans();
        assertThat(SPANS.getFinishedSpanItems())
                .anySatisfy(span -> assertThat(span.getAttributes().toString()).contains("SELECT ?::text"))
                .allSatisfy(span -> assertThat(span.getAttributes().toString())
                        .doesNotContain("synthetic-bound-value", "jdbc.params"));
    }

    @Test
    void observesBatchPopulationEvictionAndWarmHitsWithoutDatabaseWork() throws Exception {
        String serial = "TRACE-BATCH";
        repository.saveAndFlush(
                new Pricing(serial, BigDecimal.TEN, BigDecimal.ONE, 7, new BigDecimal("0.1"), BigDecimal.TEN));
        redis.delete("rentflow:pricing:v1:pricingBySerial::" + serial);
        flushSpans();
        SPANS.reset();
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/pricing/batch/cached"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("[\"" + serial + "\"]"))
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            flushSpans();
            assertThat(SPANS.getFinishedSpanItems())
                    .anySatisfy(span -> assertThat(span.getName()).isEqualTo("mget"));
            SPANS.reset();
            assertThat(get("local.server.port", "/api/v1/pricing/" + serial + "/cached")
                            .statusCode())
                    .isEqualTo(200);
            flushSpans();
            assertThat(SPANS.getFinishedSpanItems())
                    .noneSatisfy(span -> assertThat(span.getName()).contains("connection"))
                    .noneSatisfy(span -> assertThat(span.getName()).contains("query"));
            SPANS.reset();
            HttpRequest deletion = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/pricing/" + serial))
                    .DELETE()
                    .build();
            assertThat(client.send(deletion, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(204);
            flushSpans();
            assertThat(SPANS.getFinishedSpanItems())
                    .anySatisfy(span -> assertThat(span.getName()).isEqualTo("del"));
        }
    }

    @Test
    void retainsFailingSqlAndCorrelatesHandledErrorsWhileKeepingPublicDetailsGeneric() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
                    jdbc.queryForObject("SELECT 'not-an-integer'::integer", Integer.class);
                    return null;
                })
                .when(repository)
                .findById("TRACE-FAILURE");
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow")) {
            HttpResponse<String> response = get("local.server.port", "/api/v1/pricing/TRACE-FAILURE");
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("not-an-integer", "SELECT");
            flushSpans();
            assertThat(SPANS.getFinishedSpanItems()).anySatisfy(span -> {
                assertThat(span.getName()).contains("query");
                assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                assertThat(span.getAttributes().toString()).contains("SELECT 'not-an-integer'::integer");
                assertThat(span.getEvents().toString())
                        .contains("exception.message", "exception.stacktrace", "not-an-integer");
            });
            assertThat(SPANS.getFinishedSpanItems()).anySatisfy(span -> {
                assertThat(span.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.SERVER);
                assertThat(span.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
                assertThat(span.getEvents().toString()).contains("not-an-integer");
            });
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                tools.jackson.databind.JsonNode json = EcsLogCapture.encode(event, environment);
                assertThat(json.path("traceId").asText()).hasSize(32);
                assertThat(json.path("spanId").asText()).hasSize(16);
                assertThat(json.path("error").path("stack_trace").asText()).contains("not-an-integer");
            });
        } finally {
            org.mockito.Mockito.reset(repository);
        }
    }

    @Test
    void successfulWritesAndExpectedRejectionsDoNotProduceApplicationResultLogs() throws Exception {
        org.mockito.Mockito.when(inventoryGateway.exists("TRACE-WRITE")).thenReturn(true);
        repository.deleteById("TRACE-WRITE");
        String body = """
                {"serialNumber":"TRACE-WRITE","price":124.57,"weekendRate":1,
                 "longRentalCondition":7,"longRentalDiscount":0.1,"deposit":345.67}
                """;
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        try (EcsLogCapture logs = new EcsLogCapture("com.rentflow");
                HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest creation = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/pricing"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            assertThat(client.send(creation, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(201);
            assertThat(client.send(creation, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(409);
            HttpRequest replacement = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/pricing/TRACE-WRITE"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            assertThat(client.send(replacement, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(200);
            HttpRequest deletion =
                    HttpRequest.newBuilder(replacement.uri()).DELETE().build();
            assertThat(client.send(deletion, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(204);
            assertThat(get("local.server.port", "/api/v1/pricing/TRACE-WRITE").statusCode())
                    .isEqualTo(404);
            assertThat(logs.events()).isEmpty();
            flushSpans();
            assertThat(SPANS.getFinishedSpanItems())
                    .allSatisfy(span -> assertThat(span.getAttributes().toString() + span.getEvents())
                            .doesNotContain("124.57", "345.67"));
        }
    }

    private HttpResponse<String> get(String portProperty, String path) throws Exception {
        int port = environment.getRequiredProperty(portProperty, Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private void flushSpans() {
        assertThat(tracerProvider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess())
                .isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingTraces {
        @Bean
        SpanExporter recordingSpanExporter() {
            return SPANS;
        }
    }
}
