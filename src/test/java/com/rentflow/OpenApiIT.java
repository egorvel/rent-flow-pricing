package com.rentflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.rentflow.dto.PricingDTO;
import com.rentflow.support.PostgresIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenApiIT extends PostgresIntegrationTest {

    private static final Set<String> PRICING_FIELDS =
            Set.of("serialNumber", "price", "weekendRate", "longRentalCondition", "longRentalDiscount", "deposit");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode document;

    @BeforeEach
    void loadOpenApiDocument() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();
        document = objectMapper.readTree(response.getContentAsByteArray());
    }

    @Test
    void exposesOnlyTheFiveUnauthenticatedPricingOperations() {
        assertThat(document.path("openapi").asString()).startsWith("3.");
        assertThat(document.at("/info/title").asString()).isEqualTo("RentFlow Pricing API");
        assertThat(document.at("/info/version").asString()).isEqualTo("v1");
        assertThat(document.path("tags").get(0).path("name").asString()).isEqualTo("Pricing");

        JsonNode paths = document.path("paths");
        assertThat(names(paths)).containsExactlyInAnyOrder("/api/v1/pricing", "/api/v1/pricing/{serialNumber}");
        assertThat(operation("/api/v1/pricing", "post").path("operationId").asString())
                .isEqualTo("createPricing");
        assertThat(operation("/api/v1/pricing", "get").path("operationId").asString())
                .isEqualTo("listPricing");
        assertThat(operation("/api/v1/pricing/{serialNumber}", "get")
                        .path("operationId")
                        .asString())
                .isEqualTo("getPricing");
        assertThat(operation("/api/v1/pricing/{serialNumber}", "put")
                        .path("operationId")
                        .asString())
                .isEqualTo("replacePricing");
        assertThat(operation("/api/v1/pricing/{serialNumber}", "delete")
                        .path("operationId")
                        .asString())
                .isEqualTo("deletePricing");
        assertThat(paths.path("/api/v1/pricing").has("patch")).isFalse();
        assertThat(paths.path("/api/v1/pricing/{serialNumber}").has("patch")).isFalse();
        assertThat(names(paths))
                .noneMatch(path -> path.startsWith("/actuator") || path.equals("/livez") || path.equals("/readyz"));

        assertThat(document.has("security")).isFalse();
        assertThat(document.at("/components/securitySchemes").isMissingNode()
                        || document.at("/components/securitySchemes").isEmpty())
                .isTrue();
        for (JsonNode operation : operations()) {
            assertThat(operation.has("security")).isFalse();
        }
    }

    @Test
    void locksBusinessAndProblemSchemas() {
        assertSchemaProperties("PricingDTO", PRICING_FIELDS);
        assertThat(texts(schema("PricingDTO").path("required"))).containsExactlyInAnyOrderElementsOf(PRICING_FIELDS);

        JsonNode requestProperties = schema("PricingDTO").path("properties");
        JsonNode serialNumber = resolved(requestProperties.path("serialNumber"));
        assertThat(serialNumber.path("minLength").asInt()).isEqualTo(1);
        assertThat(serialNumber.path("maxLength").asInt()).isEqualTo(64);
        assertThat(serialNumber.path("pattern").asString()).isEqualTo(PricingDTO.SERIAL_NUMBER_PATTERN);
        assertNumericSchema(requestProperties.path("price"), "0", null, true, false);
        assertNumericSchema(requestProperties.path("weekendRate"), "0", null, true, false);
        JsonNode longRentalCondition = resolved(requestProperties.path("longRentalCondition"));
        assertThat(longRentalCondition.path("type").asString()).isEqualTo("integer");
        assertThat(longRentalCondition.path("minimum").asString()).isEqualTo("1");
        assertNumericSchema(requestProperties.path("longRentalDiscount"), "0", "1", false, true);
        assertNumericSchema(requestProperties.path("deposit"), "0", null, false, false);

        JsonNode pageResponse =
                resolved(responseSchema(operation("/api/v1/pricing", "get"), "200", MediaType.APPLICATION_JSON_VALUE));
        assertThat(names(pageResponse.path("properties"))).containsExactlyInAnyOrder("content", "page");
        JsonNode contentSchema = resolved(pageResponse.path("properties").path("content"));
        assertThat(contentSchema.path("type").asString()).isEqualTo("array");
        assertThat(contentSchema.path("items").path("$ref").asString()).endsWith("/PricingDTO");
        JsonNode pageMetadata = resolved(pageResponse.path("properties").path("page"));
        assertThat(names(pageMetadata.path("properties")))
                .containsExactlyInAnyOrder("size", "number", "totalElements", "totalPages");
        assertSchemaProperties(
                "ProblemResponse", Set.of("type", "title", "status", "detail", "instance", "code", "violations"));
        assertThat(texts(schema("ProblemResponse").path("required")))
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertSchemaProperties("ViolationResponse", Set.of("field", "message"));
    }

    @Test
    void documentsPaginationSortingAndRequestBodies() {
        JsonNode list = operation("/api/v1/pricing", "get");
        assertThat(parameterNames(list)).containsExactlyInAnyOrder("page", "size", "sort", "direction");

        assertParameter(parameter(list, "page"), "0", "0", null);
        assertParameter(parameter(list, "size"), "20", "1", "100");
        assertThat(resolved(parameter(list, "sort").path("schema"))
                        .path("default")
                        .asString())
                .isEqualTo("serialNumber");
        assertThat(enumValues(parameter(list, "sort").path("schema")))
                .containsExactlyInAnyOrder(
                        "serialNumber", "price", "weekendRate", "longRentalCondition", "longRentalDiscount", "deposit");
        assertThat(resolved(parameter(list, "direction").path("schema"))
                        .path("default")
                        .asString())
                .isEqualTo("asc");
        assertThat(enumValues(parameter(list, "direction").path("schema"))).containsExactlyInAnyOrder("asc", "desc");

        for (String method : List.of("get", "put", "delete")) {
            JsonNode serialParameter = parameter(operation("/api/v1/pricing/{serialNumber}", method), "serialNumber");
            assertThat(serialParameter.path("required").asBoolean()).isTrue();
            assertThat(resolved(serialParameter.path("schema")).path("pattern").asString())
                    .isEqualTo(PricingDTO.SERIAL_NUMBER_PATTERN);
        }

        assertRequestBodySchema(operation("/api/v1/pricing", "post"), "PricingDTO");
        assertRequestBodySchema(operation("/api/v1/pricing/{serialNumber}", "put"), "PricingDTO");
    }

    @Test
    void documentsSuccessAndApplicableProblemResponses() {
        JsonNode create = operation("/api/v1/pricing", "post");
        assertResponseCodes(create, "201", "400", "406", "409", "415", "500");
        assertResponseSchema(create, "201", MediaType.APPLICATION_JSON_VALUE, "PricingDTO");
        assertThat(create.at("/responses/201/headers/Location/schema/format").asString())
                .isEqualTo("uri");

        JsonNode list = operation("/api/v1/pricing", "get");
        assertResponseCodes(list, "200", "400", "406", "500");
        JsonNode pageResponse = resolved(responseSchema(list, "200", MediaType.APPLICATION_JSON_VALUE));
        assertThat(names(pageResponse.path("properties"))).containsExactlyInAnyOrder("content", "page");

        JsonNode get = operation("/api/v1/pricing/{serialNumber}", "get");
        assertResponseCodes(get, "200", "400", "404", "406", "500");
        assertResponseSchema(get, "200", MediaType.APPLICATION_JSON_VALUE, "PricingDTO");

        JsonNode replace = operation("/api/v1/pricing/{serialNumber}", "put");
        assertResponseCodes(replace, "200", "400", "404", "406", "415", "500");
        assertResponseSchema(replace, "200", MediaType.APPLICATION_JSON_VALUE, "PricingDTO");

        JsonNode delete = operation("/api/v1/pricing/{serialNumber}", "delete");
        assertResponseCodes(delete, "204", "400", "404", "500");
        assertThat(delete.at("/responses/204").has("content")).isFalse();

        assertProblemSchemas(create, Set.of("400", "406", "409", "415", "500"));
        assertProblemSchemas(list, Set.of("400", "406", "500"));
        assertProblemSchemas(get, Set.of("400", "404", "406", "500"));
        assertProblemSchemas(replace, Set.of("400", "404", "406", "415", "500"));
        assertProblemSchemas(delete, Set.of("400", "404", "500"));
    }

    @Test
    void publishesSchemaConformingExamplesAndInteractiveSwaggerUi() throws Exception {
        JsonNode pricingExample = schemaExample("PricingDTO");
        assertExampleMatchesSchema(pricingExample, schema("PricingDTO"));
        assertThat(pricingExample.path("serialNumber").asString()).isEqualTo("DRILL-001");
        assertThat(pricingExample.path("price").decimalValue()).isEqualByComparingTo("125.50");
        assertThat(pricingExample.path("longRentalCondition").asInt()).isEqualTo(7);

        JsonNode problemExample = schemaExample("ProblemResponse");
        assertExampleMatchesSchema(problemExample, schema("ProblemResponse"));
        assertThat(problemExample.path("type").asString()).isEqualTo("urn:rentflow:problem:validation-failed");
        assertThat(problemExample.path("code").asString()).isEqualTo("VALIDATION_FAILED");

        String redirect = mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(redirect).isNotBlank();
        mockMvc.perform(get(redirect))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }

    private List<JsonNode> operations() {
        return List.of(
                operation("/api/v1/pricing", "post"),
                operation("/api/v1/pricing", "get"),
                operation("/api/v1/pricing/{serialNumber}", "get"),
                operation("/api/v1/pricing/{serialNumber}", "put"),
                operation("/api/v1/pricing/{serialNumber}", "delete"));
    }

    private JsonNode schema(String name) {
        return document.at("/components/schemas/" + name);
    }

    private void assertSchemaProperties(String name, Set<String> expected) {
        assertThat(names(schema(name).path("properties"))).containsExactlyInAnyOrderElementsOf(expected);
    }

    private Set<String> names(JsonNode object) {
        return new LinkedHashSet<>(object.propertyNames());
    }

    private Set<String> texts(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Set<String> enumValues(JsonNode rawSchema) {
        JsonNode valueSchema = resolved(rawSchema);
        Set<String> values = texts(valueSchema.path("enum"));
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            valueSchema.path(composition).forEach(schema -> values.addAll(enumValues(schema)));
        }
        return values;
    }

    private JsonNode resolved(JsonNode schema) {
        String reference = schema.path("$ref").asString();
        return reference.isEmpty() ? schema : document.at(reference.substring(1));
    }

    private Set<String> parameterNames(JsonNode operation) {
        Set<String> names = new LinkedHashSet<>();
        operation
                .path("parameters")
                .forEach(parameter -> names.add(parameter.path("name").asString()));
        return names;
    }

    private JsonNode parameter(JsonNode operation, String name) {
        for (JsonNode parameter : operation.path("parameters")) {
            if (name.equals(parameter.path("name").asString())) {
                return parameter;
            }
        }
        throw new AssertionError("Missing OpenAPI parameter: " + name);
    }

    private void assertParameter(JsonNode parameter, String defaultValue, String minimum, String maximum) {
        JsonNode parameterSchema = resolved(parameter.path("schema"));
        assertThat(parameterSchema.path("default").asString()).isEqualTo(defaultValue);
        assertThat(parameterSchema.path("minimum").asString()).isEqualTo(minimum);
        if (maximum == null) {
            assertThat(parameterSchema.has("maximum")).isFalse();
        } else {
            assertThat(parameterSchema.path("maximum").asString()).isEqualTo(maximum);
        }
    }

    private void assertNumericSchema(
            JsonNode rawSchema, String minimum, String maximum, boolean exclusiveMinimum, boolean exclusiveMaximum) {
        JsonNode numericSchema = resolved(rawSchema);
        assertThat(numericSchema.path("type").asString()).isEqualTo("number");
        assertThat(numericSchema.path("minimum").asString()).isEqualTo(minimum);
        if (exclusiveMinimum) {
            assertThat(numericSchema.path("exclusiveMinimum").asString()).isEqualTo(minimum);
        } else {
            assertThat(numericSchema.has("exclusiveMinimum")).isFalse();
        }
        if (maximum == null) {
            assertThat(numericSchema.has("maximum")).isFalse();
        } else {
            assertThat(numericSchema.path("maximum").asString()).isEqualTo(maximum);
        }
        if (exclusiveMaximum) {
            assertThat(numericSchema.path("exclusiveMaximum").asString()).isEqualTo(maximum);
        } else {
            assertThat(numericSchema.has("exclusiveMaximum")).isFalse();
        }
    }

    private void assertRequestBodySchema(JsonNode operation, String schemaName) {
        assertThat(operation.at("/requestBody/required").asBoolean()).isTrue();
        assertThat(operation
                        .at("/requestBody/content/application~1json/schema/$ref")
                        .asString())
                .endsWith("/" + schemaName);
    }

    private void assertResponseCodes(JsonNode operation, String... responseCodes) {
        assertThat(names(operation.path("responses"))).containsExactlyInAnyOrder(responseCodes);
    }

    private void assertResponseSchema(JsonNode operation, String status, String mediaType, String schemaName) {
        assertThat(responseSchema(operation, status, mediaType).path("$ref").asString())
                .endsWith("/" + schemaName);
    }

    private JsonNode responseSchema(JsonNode operation, String status, String mediaType) {
        String escapedMediaType = mediaType.replace("/", "~1");
        return operation.at("/responses/" + status + "/content/" + escapedMediaType + "/schema");
    }

    private void assertProblemSchemas(JsonNode operation, Set<String> responseCodes) {
        for (String responseCode : responseCodes) {
            assertResponseSchema(operation, responseCode, MediaType.APPLICATION_PROBLEM_JSON_VALUE, "ProblemResponse");
        }
    }

    private JsonNode schemaExample(String schemaName) throws Exception {
        JsonNode example = schema(schemaName).path("example");
        if (example.isMissingNode() && schema(schemaName).path("examples").isArray()) {
            example = schema(schemaName).path("examples").get(0);
        }
        assertThat(example.isMissingNode()).isFalse();
        if (example.isString()) {
            return objectMapper.readTree(example.asString());
        }
        return example;
    }

    private void assertExampleMatchesSchema(JsonNode example, JsonNode rawSchema) {
        JsonNode exampleSchema = resolved(rawSchema);
        if (exampleSchema.has("properties")) {
            assertThat(example.isObject()).isTrue();
            assertThat(names(example)).isSubsetOf(names(exampleSchema.path("properties")));
            assertThat(names(example)).containsAll(texts(exampleSchema.path("required")));
            example.forEachEntry((name, value) -> assertExampleMatchesSchema(
                    value, exampleSchema.path("properties").path(name)));
            return;
        }
        if ("array".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isArray()).isTrue();
            example.forEach(item -> assertExampleMatchesSchema(item, exampleSchema.path("items")));
            return;
        }
        if ("integer".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isIntegralNumber()).isTrue();
            return;
        }
        if ("number".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isNumber()).isTrue();
            return;
        }
        if ("string".equals(exampleSchema.path("type").asString())) {
            assertThat(example.isString()).isTrue();
            Set<String> allowedValues = enumValues(exampleSchema);
            if (!allowedValues.isEmpty()) {
                assertThat(allowedValues).contains(example.asString());
            }
        }
    }
}
