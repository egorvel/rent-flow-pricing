package com.rentflow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

import com.rentflow.support.PostgresIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@AutoConfigureTracing
@Import(ObservabilityIT.RecordingTraces.class)
class ObservabilityIT extends PostgresIntegrationTest {
    private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

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
