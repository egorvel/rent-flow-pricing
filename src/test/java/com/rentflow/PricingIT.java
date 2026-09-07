package com.rentflow;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.service.InvalidInventoryReferenceException;
import com.rentflow.service.InventoryGateway;
import com.rentflow.service.InventoryServiceResponseException;
import com.rentflow.service.InventoryServiceUnavailableException;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PricingIT extends PostgresIntegrationTest {

    private static final String CONFLICT_SERIAL = "DRILL-CONFLICT";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PricingRepository repository;

    @MockitoBean
    private InventoryGateway inventoryGateway;

    @BeforeEach
    void clearPricing() {
        repository.deleteAllInBatch();
        when(inventoryGateway.exists(anyString())).thenReturn(true);
    }

    @Test
    void createsAndRetrievesPricingWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pricing/DRILL-001"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.price").value(125.50))
                .andExpect(jsonPath("$.weekendRate").value(1.2500))
                .andExpect(jsonPath("$.longRentalCondition").value(7))
                .andExpect(jsonPath("$.longRentalDiscount").value(0.1000))
                .andExpect(jsonPath("$.deposit").value(300.00));

        mockMvc.perform(get("/api/v1/pricing/DRILL-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.price").value(125.50))
                .andExpect(jsonPath("$.weekendRate").value(1.2500))
                .andExpect(jsonPath("$.longRentalCondition").value(7))
                .andExpect(jsonPath("$.longRentalDiscount").value(0.1000))
                .andExpect(jsonPath("$.deposit").value(300.00));
    }

    @Test
    void keepsSerialNumbersCaseSensitive() throws Exception {
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("DRILL-001", "100.00", "1.0000", 7, "0.1000", "200.00")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("drill-001", "200.00", "1.0000", 7, "0.1000", "200.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/pricing/DRILL-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(100.00));
        mockMvc.perform(get("/api/v1/pricing/drill-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200.00));
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void rejectsInvalidCreateBodiesWithoutPersisting(String body) throws Exception {
        assertInvalidCreate(body);
        assertThat(repository.count()).isZero();
    }

    @Test
    void returnsAllMissingFieldsAsSortedViolations() throws Exception {
        expectValidation(
                        mockMvc.perform(post("/api/v1/pricing")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")),
                        "/api/v1/pricing")
                .andExpect(jsonPath("$.violations", hasSize(6)))
                .andExpect(jsonPath("$.violations[0].field").value("deposit"))
                .andExpect(jsonPath("$.violations[1].field").value("longRentalCondition"))
                .andExpect(jsonPath("$.violations[2].field").value("longRentalDiscount"))
                .andExpect(jsonPath("$.violations[3].field").value("price"))
                .andExpect(jsonPath("$.violations[4].field").value("serialNumber"))
                .andExpect(jsonPath("$.violations[5].field").value("weekendRate"));
    }

    @Test
    void distinguishesWrongFieldTypesFromMalformedJson() throws Exception {
        expectValidation(
                        mockMvc.perform(post("/api/v1/pricing")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestWithPrice("DRILL-001", "\"not-a-number\""))),
                        "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("price"))
                .andExpect(jsonPath("$.violations[0].message").value("must have a valid value"));

        expectValidation(
                        mockMvc.perform(post("/api/v1/pricing")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "serialNumber": "DRILL-001",
                                          "price": 125.50,
                                          "weekendRate": 1.2500,
                                          "longRentalCondition": 1.5,
                                          "longRentalDiscount": 0.1000,
                                          "deposit": 300.00
                                        }
                                        """)),
                        "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("longRentalCondition"));

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{")),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "/api/v1/pricing",
                "MALFORMED_JSON");
    }

    @Test
    void rejectsInvalidAndMissingSerialNumbers() throws Exception {
        expectValidation(mockMvc.perform(get(URI.create("/api/v1/pricing/%20bad%20"))), "/api/v1/pricing/%20bad%20")
                .andExpect(jsonPath("$.violations[0].field").value("serialNumber"));

        expectProblem(
                mockMvc.perform(get("/api/v1/pricing/MISSING")),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:pricing-not-found",
                "Pricing not found",
                "Pricing for serial number 'MISSING' was not found.",
                "/api/v1/pricing/MISSING",
                "PRICING_NOT_FOUND");
    }

    @Test
    void replacesAndPersistsEveryMutableFieldWhilePreservingIdentity() throws Exception {
        repository.saveAndFlush(pricing("DRILL-001", "125.50", "1.2500", 7, "0.1000", "300.00"));

        mockMvc.perform(put("/api/v1/pricing/DRILL-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("DRILL-001", "150.00", "1.5000", 14, "0.2500", "450.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.price").value(150.00))
                .andExpect(jsonPath("$.weekendRate").value(1.5000))
                .andExpect(jsonPath("$.longRentalCondition").value(14))
                .andExpect(jsonPath("$.longRentalDiscount").value(0.2500))
                .andExpect(jsonPath("$.deposit").value(450.00));

        Pricing persisted = repository.findById("DRILL-001").orElseThrow();
        assertThat(persisted.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(persisted.getPrice()).isEqualByComparingTo("150.00");
        assertThat(persisted.getWeekendRate()).isEqualByComparingTo("1.5000");
        assertThat(persisted.getLongRentalCondition()).isEqualTo(14);
        assertThat(persisted.getLongRentalDiscount()).isEqualByComparingTo("0.2500");
        assertThat(persisted.getDeposit()).isEqualByComparingTo("450.00");
    }

    @Test
    void rejectedReplacementsLeaveTheOriginalUnchanged() throws Exception {
        repository.saveAndFlush(pricing("DRILL-001", "125.50", "1.2500", 7, "0.1000", "300.00"));

        assertInvalidReplacement(request("drill-001", "150.00", "1.5000", 14, "0.2500", "450.00"));
        assertInvalidReplacement(request("OTHER-001", "150.00", "1.5000", 14, "0.2500", "450.00"));
        assertInvalidReplacement(request("DRILL-001", "0.00", "1.5000", 14, "0.2500", "450.00"));
        assertInvalidReplacement("{");

        Pricing persisted = repository.findById("DRILL-001").orElseThrow();
        assertThat(persisted.getPrice()).isEqualByComparingTo("125.50");
        assertThat(persisted.getWeekendRate()).isEqualByComparingTo("1.2500");
        assertThat(persisted.getLongRentalCondition()).isEqualTo(7);
        assertThat(persisted.getLongRentalDiscount()).isEqualByComparingTo("0.1000");
        assertThat(persisted.getDeposit()).isEqualByComparingTo("300.00");
        assertThat(repository.count()).isOne();
    }

    @Test
    void doesNotUpsertWhenReplacingMissingPricing() throws Exception {
        expectProblem(
                mockMvc.perform(put("/api/v1/pricing/MISSING")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("MISSING"))),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:pricing-not-found",
                "Pricing not found",
                "Pricing for serial number 'MISSING' was not found.",
                "/api/v1/pricing/MISSING",
                "PRICING_NOT_FOUND");

        assertThat(repository.count()).isZero();
    }

    @Test
    void permanentlyDeletesPricingAndRejectsMissingDeletes() throws Exception {
        repository.saveAndFlush(pricing("DRILL-001", "125.50", "1.2500", 7, "0.1000", "300.00"));

        mockMvc.perform(delete("/api/v1/pricing/DRILL-001"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/v1/pricing/DRILL-001")).andExpect(status().isNotFound());

        expectProblem(
                mockMvc.perform(delete("/api/v1/pricing/MISSING")),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:pricing-not-found",
                "Pricing not found",
                "Pricing for serial number 'MISSING' was not found.",
                "/api/v1/pricing/MISSING",
                "PRICING_NOT_FOUND");
    }

    @Test
    void returnsStableConflictAndPreservesTheOriginal() throws Exception {
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictRequest("125.50")))
                .andExpect(status().isCreated());

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictRequest("999.99"))),
                HttpStatus.CONFLICT,
                "urn:rentflow:problem:pricing-already-exists",
                "Pricing already exists",
                "Pricing for serial number 'DRILL-CONFLICT' already exists.",
                "/api/v1/pricing",
                "PRICING_ALREADY_EXISTS");

        assertThat(repository.findById(CONFLICT_SERIAL).orElseThrow().getPrice())
                .isEqualByComparingTo("125.50");
    }

    @Test
    void mapsAMissingInventoryItemToUnprocessableContent() throws Exception {
        when(inventoryGateway.exists("MISSING")).thenReturn(false);

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("MISSING"))),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "urn:rentflow:problem:inventory-item-not-found",
                "Inventory item not found",
                "Inventory item 'MISSING' was not found.",
                "/api/v1/pricing",
                "INVENTORY_ITEM_NOT_FOUND");

        assertThat(repository.count()).isZero();
    }

    @Test
    void mapsAnInventoryBadRequestToBadRequest() throws Exception {
        when(inventoryGateway.exists("DRILL-001"))
                .thenThrow(new InvalidInventoryReferenceException("DRILL-001", new IllegalArgumentException()));

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001"))),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:invalid-inventory-reference",
                "Invalid inventory reference",
                "Inventory rejected serial number 'DRILL-001' as invalid.",
                "/api/v1/pricing",
                "INVALID_INVENTORY_REFERENCE");

        assertThat(repository.count()).isZero();
    }

    @Test
    void mapsOtherInventoryClientResponsesToBadGateway() throws Exception {
        when(inventoryGateway.exists("DRILL-001"))
                .thenThrow(new InventoryServiceResponseException("DRILL-001", new IllegalArgumentException()));

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001"))),
                HttpStatus.BAD_GATEWAY,
                "urn:rentflow:problem:inventory-service-error",
                "Inventory service error",
                "Inventory service returned an unexpected response.",
                "/api/v1/pricing",
                "INVENTORY_SERVICE_ERROR");

        assertThat(repository.count()).isZero();
    }

    @Test
    void mapsInventoryAvailabilityFailuresToServiceUnavailable() throws Exception {
        when(inventoryGateway.exists("DRILL-001"))
                .thenThrow(new InventoryServiceUnavailableException("DRILL-001", new IllegalStateException()));

        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("DRILL-001"))),
                HttpStatus.SERVICE_UNAVAILABLE,
                "urn:rentflow:problem:inventory-service-unavailable",
                "Inventory service unavailable",
                "Inventory service is temporarily unavailable.",
                "/api/v1/pricing",
                "INVENTORY_SERVICE_UNAVAILABLE");

        assertThat(repository.count()).isZero();
    }

    @Test
    void concurrentCreateHasOneWinnerAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = List.of("125.50", "999.99").stream()
                    .map(price -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return mockMvc.perform(post("/api/v1/pricing")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conflictRequest(price)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();

            ready.await();
            start.countDown();

            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
        }

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void returnsDefaultPageWithDeterministicOrder() throws Exception {
        seedPricing();

        mockMvc.perform(get("/api/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(2)))
                .andExpect(jsonPath("$.content[*].serialNumber", contains("A-100", "B-200", "C-300", "D-400")))
                .andExpect(jsonPath("$.page", aMapWithSize(4)))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void supportsBoundedPagesEmptyDataAndPagesPastTheEnd() throws Exception {
        seedPricing();

        mockMvc.perform(get("/api/v1/pricing").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains("C-300", "D-400")))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/pricing").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.number").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(4));

        repository.deleteAllInBatch();
        mockMvc.perform(get("/api/v1/pricing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @ParameterizedTest
    @MethodSource("sortCases")
    void supportsEveryAllowlistedSortInBothDirections(String field, String direction, List<String> expectedSerials)
            throws Exception {
        seedPricing();

        mockMvc.perform(get("/api/v1/pricing").param("sort", field).param("direction", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].serialNumber", contains(expectedSerials.toArray())));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidRepeatedUnknownAndFilteringQueryParameters(String query) throws Exception {
        mockMvc.perform(get("/api/v1/pricing?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").isNotEmpty());
    }

    @Test
    void returnsExactPathAndQueryViolations() throws Exception {
        expectValidation(mockMvc.perform(get("/api/v1/pricing").param("page", "not-a-number")), "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("page"))
                .andExpect(jsonPath("$.violations[0].message").value("must have a valid value"));
        expectValidation(mockMvc.perform(get("/api/v1/pricing").param("unexpected", "value")), "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("unexpected"))
                .andExpect(jsonPath("$.violations[0].message").value("is not supported"));
        expectValidation(mockMvc.perform(get("/api/v1/pricing").param("sort", "price", "deposit")), "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("sort"))
                .andExpect(jsonPath("$.violations[0].message").value("must be supplied exactly once"));
    }

    @Test
    void mapsMediaNegotiationAndUnsupportedMethods() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "urn:rentflow:problem:unsupported-media-type",
                "Unsupported media type",
                "The request media type is not supported.",
                "/api/v1/pricing",
                "UNSUPPORTED_MEDIA_TYPE");

        expectProblem(
                mockMvc.perform(get("/api/v1/pricing").accept(MediaType.TEXT_PLAIN)),
                HttpStatus.NOT_ACCEPTABLE,
                "urn:rentflow:problem:not-acceptable",
                "Not acceptable",
                "No acceptable response representation is available.",
                "/api/v1/pricing",
                "NOT_ACCEPTABLE");

        MvcResult patchResult = expectProblem(
                        mockMvc.perform(patch("/api/v1/pricing/DRILL-001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequest("DRILL-001"))),
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "urn:rentflow:problem:method-not-allowed",
                        "Method not allowed",
                        "The HTTP method is not supported for this resource.",
                        "/api/v1/pricing/DRILL-001",
                        "METHOD_NOT_ALLOWED")
                .andReturn();
        assertThat(patchResult.getResponse().getHeader(HttpHeaders.ALLOW))
                .contains("GET")
                .contains("PUT")
                .contains("DELETE");
    }

    @Test
    void mapsUnknownJsonAndUnmappedResourcesWithoutInternalDetails() throws Exception {
        expectProblem(
                mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serialNumber": "DRILL-001",
                                  "price": 125.50,
                                  "weekendRate": 1.2500,
                                  "longRentalCondition": 7,
                                  "longRentalDiscount": 0.1000,
                                  "deposit": 300.00,
                                  "unknown": true
                                }
                                """)),
                HttpStatus.BAD_REQUEST,
                "urn:rentflow:problem:malformed-json",
                "Malformed JSON",
                "The request body could not be read.",
                "/api/v1/pricing",
                "MALFORMED_JSON");

        expectResourceNotFound("/api/v1/not-a-resource");
    }

    @Test
    void everyPricingOperationIsReachableWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("NOAUTH-001")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/pricing")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/pricing/NOAUTH-001")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/pricing/NOAUTH-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("NOAUTH-001", "150.00", "1.5000", 14, "0.2500", "450.00")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/pricing/NOAUTH-001")).andExpect(status().isNoContent());
    }

    @Test
    void authenticationCalculationAndFilteringCapabilitiesRemainUnmappedOrRejected() throws Exception {
        for (String path : new String[] {
            "/login",
            "/api/v1/token",
            "/api/v1/users",
            "/api/v1/roles",
            "/api/v1/permissions",
            "/api/v1/pricing/calculate/DRILL-001"
        }) {
            expectResourceNotFound(path);
        }

        expectValidation(mockMvc.perform(get("/api/v1/pricing").param("price", "125.50")), "/api/v1/pricing")
                .andExpect(jsonPath("$.violations[0].field").value("price"));
    }

    private void assertInvalidCreate(String body) throws Exception {
        mockMvc.perform(post("/api/v1/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").isNotEmpty())
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/pricing"))
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    private void assertInvalidReplacement(String body) throws Exception {
        mockMvc.perform(put("/api/v1/pricing/DRILL-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private void seedPricing() {
        repository.saveAllAndFlush(List.of(
                pricing("A-100", "100.00", "1.0000", 7, "0.1000", "200.00"),
                pricing("B-200", "200.00", "1.2500", 14, "0.2000", "300.00"),
                pricing("C-300", "100.00", "1.5000", 7, "0.1000", "100.00"),
                pricing("D-400", "300.00", "1.0000", 30, "0.3000", "300.00")));
    }

    private static Stream<Arguments> sortCases() {
        return Stream.of(
                Arguments.of("serialNumber", "asc", List.of("A-100", "B-200", "C-300", "D-400")),
                Arguments.of("serialNumber", "DESC", List.of("D-400", "C-300", "B-200", "A-100")),
                Arguments.of("price", "asc", List.of("A-100", "C-300", "B-200", "D-400")),
                Arguments.of("price", "desc", List.of("D-400", "B-200", "A-100", "C-300")),
                Arguments.of("weekendRate", "asc", List.of("A-100", "D-400", "B-200", "C-300")),
                Arguments.of("weekendRate", "desc", List.of("C-300", "B-200", "A-100", "D-400")),
                Arguments.of("longRentalCondition", "asc", List.of("A-100", "C-300", "B-200", "D-400")),
                Arguments.of("longRentalCondition", "desc", List.of("D-400", "B-200", "A-100", "C-300")),
                Arguments.of("longRentalDiscount", "asc", List.of("A-100", "C-300", "B-200", "D-400")),
                Arguments.of("longRentalDiscount", "desc", List.of("D-400", "B-200", "A-100", "C-300")),
                Arguments.of("deposit", "asc", List.of("C-300", "A-100", "B-200", "D-400")),
                Arguments.of("deposit", "desc", List.of("B-200", "D-400", "A-100", "C-300")));
    }

    private static Stream<String> invalidQueries() {
        return Stream.of(
                "page=-1",
                "page=not-a-number",
                "size=0",
                "size=101",
                "sort=",
                "sort=unknown",
                "direction=sideways",
                "unknown=value",
                "page=0&page=1",
                "price=125.50",
                "status=AVAILABLE");
    }

    private static Stream<String> invalidCreateBodies() {
        return Stream.of(
                request(" bad ", "125.50", "1.2500", 7, "0.1000", "300.00"),
                request("DRILL-001", "0.00", "1.2500", 7, "0.1000", "300.00"),
                request("DRILL-001", "125.50", "0.0000", 7, "0.1000", "300.00"),
                request("DRILL-001", "125.50", "1.2500", 0, "0.1000", "300.00"),
                request("DRILL-001", "125.50", "1.2500", 7, "1.0000", "300.00"),
                request("DRILL-001", "125.501", "1.2500", 7, "0.1000", "300.00"),
                request("DRILL-001", "125.50", "1.25001", 7, "0.1000", "300.00"),
                request("DRILL-001", "125.50", "1.2500", 7, "0.10001", "300.00"),
                request("DRILL-001", "125.50", "1.2500", 7, "0.1000", "-0.01"),
                "{}",
                """
                {
                  "serialNumber": "DRILL-001",
                  "price": 125.50,
                  "weekendRate": 1.2500,
                  "longRentalCondition": 7,
                  "longRentalDiscount": 0.1000,
                  "deposit": 300.00,
                  "extra": true
                }
                """,
                "{");
    }

    private ResultActions expectValidation(ResultActions actions, String instance) throws Exception {
        return actions.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(7)))
                .andExpect(jsonPath("$.type").value("urn:rentflow:problem:validation-failed"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("One or more request values are invalid."))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isNotEmpty());
    }

    private ResultActions expectProblem(
            ResultActions actions,
            HttpStatus status,
            String type,
            String title,
            String detail,
            String instance,
            String code)
            throws Exception {
        return actions.andExpect(status().is(status.value()))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status.value()))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    private void expectResourceNotFound(String path) throws Exception {
        expectProblem(
                mockMvc.perform(get(path)),
                HttpStatus.NOT_FOUND,
                "urn:rentflow:problem:resource-not-found",
                "Resource not found",
                "The requested resource was not found.",
                path,
                "RESOURCE_NOT_FOUND");
    }

    private String validRequest(String serialNumber) {
        return request(serialNumber, "125.50", "1.2500", 7, "0.1000", "300.00");
    }

    private String conflictRequest(String price) {
        return request(CONFLICT_SERIAL, price, "1.2500", 7, "0.1000", "300.00");
    }

    private String requestWithPrice(String serialNumber, String priceJson) {
        return """
                {
                  "serialNumber": "%s",
                  "price": %s,
                  "weekendRate": 1.2500,
                  "longRentalCondition": 7,
                  "longRentalDiscount": 0.1000,
                  "deposit": 300.00
                }
                """.formatted(serialNumber, priceJson);
    }

    private static String request(
            String serialNumber,
            String price,
            String weekendRate,
            int longRentalCondition,
            String longRentalDiscount,
            String deposit) {
        return """
                {
                  "serialNumber": "%s",
                  "price": %s,
                  "weekendRate": %s,
                  "longRentalCondition": %d,
                  "longRentalDiscount": %s,
                  "deposit": %s
                }
                """.formatted(serialNumber, price, weekendRate, longRentalCondition, longRentalDiscount, deposit);
    }

    private Pricing pricing(
            String serialNumber,
            String price,
            String weekendRate,
            int longRentalCondition,
            String longRentalDiscount,
            String deposit) {
        return new Pricing(
                serialNumber,
                new BigDecimal(price),
                new BigDecimal(weekendRate),
                longRentalCondition,
                new BigDecimal(longRentalDiscount),
                new BigDecimal(deposit));
    }
}
