package com.rentflow.controller;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.rentflow.converter.PricingConverter;
import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.service.PricingNotFoundException;
import com.rentflow.service.PricingService;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PricingControllerTest {

    @Mock
    private PricingService service;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMvc() {
        PricingController controller = new PricingController(service, new PricingConverter());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void routesCachedAndAuthoritativePathsIndependently() throws Exception {
        when(service.getCached("DRILL-001")).thenReturn(PricingCacheEntry.from(pricing("DRILL-001", "125.50")));
        when(service.get("cached")).thenReturn(pricing("cached", "150.00"));

        mockMvc.perform(get("/api/v1/pricing/DRILL-001/cached"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$.price").value(125.50))
                .andExpect(jsonPath("$.weekendRate").value(1.2500))
                .andExpect(jsonPath("$.longRentalCondition").value(7))
                .andExpect(jsonPath("$.longRentalDiscount").value(0.1000))
                .andExpect(jsonPath("$.deposit").value(300.00))
                .andExpect(jsonPath("$.source").doesNotExist())
                .andExpect(jsonPath("$.fallback").doesNotExist())
                .andExpect(jsonPath("$.hit").doesNotExist())
                .andExpect(jsonPath("$.miss").doesNotExist())
                .andExpect(header().doesNotExist("X-Cache"))
                .andExpect(header().doesNotExist("Age"));

        mockMvc.perform(get("/api/v1/pricing/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("cached"))
                .andExpect(jsonPath("$.price").value(150.00));

        verify(service).getCached("DRILL-001");
        verify(service).get("cached");
    }

    @Test
    void cachedRouteReusesNotFoundContentNegotiationAndSanitizedErrors() throws Exception {
        when(service.getCached("MISSING")).thenThrow(new PricingNotFoundException("MISSING"));
        when(service.getCached("FAIL")).thenThrow(new IllegalStateException("sensitive provider detail"));

        mockMvc.perform(get("/api/v1/pricing/MISSING/cached"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/pricing/MISSING/cached"));
        mockMvc.perform(get("/api/v1/pricing/DRILL-001/cached").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mockMvc.perform(get("/api/v1/pricing/FAIL/cached"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("sensitive provider detail"))));
    }

    @Test
    void batchDeduplicatesCaseSensitivelyAndReturnsFullPricingInFirstOccurrenceOrder() throws Exception {
        when(service.getCachedBatch(List.of("SAW-002", "DRILL-001", "saw-002")))
                .thenReturn(List.of(
                        PricingCacheEntry.from(pricing("SAW-002", "75.00")),
                        PricingCacheEntry.from(pricing("DRILL-001", "125.50")),
                        PricingCacheEntry.from(pricing("saw-002", "80.00"))));
        when(service.getCached("batch")).thenReturn(PricingCacheEntry.from(pricing("batch", "50.00")));

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"SAW-002\",\"DRILL-001\",\"SAW-002\",\"saw-002\"]"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].serialNumber").value("SAW-002"))
                .andExpect(jsonPath("$[0]", aMapWithSize(6)))
                .andExpect(jsonPath("$[0].price").value(75.00))
                .andExpect(jsonPath("$[0].weekendRate").value(1.2500))
                .andExpect(jsonPath("$[0].longRentalCondition").value(7))
                .andExpect(jsonPath("$[0].longRentalDiscount").value(0.1000))
                .andExpect(jsonPath("$[0].deposit").value(300.00))
                .andExpect(jsonPath("$[1].serialNumber").value("DRILL-001"))
                .andExpect(jsonPath("$[2].serialNumber").value("saw-002"))
                .andExpect(header().doesNotExist("X-Cache"));
        mockMvc.perform(get("/api/v1/pricing/batch/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("batch"));

        verify(service).getCachedBatch(List.of("SAW-002", "DRILL-001", "saw-002"));
        verify(service).getCached("batch");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"[]", "{}", "null", "[null]", "[123]", "[true]", "[\"bad serial\"]", "[\"GOOD\",\"bad serial\"]"
            })
    void rejectsInvalidBatchBodiesBeforeLookingUpAnyPrice(String body) throws Exception {
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(service);
    }

    @Test
    void acceptsOneHundredInputElementsBeforeDeduplicationButRejectsOneHundredAndOne() throws Exception {
        String hundred = "[" + String.join(",", Collections.nCopies(100, "\"DRILL-001\"")) + "]";
        String hundredAndOne = "[" + String.join(",", Collections.nCopies(101, "\"DRILL-001\"")) + "]";
        when(service.getCachedBatch(List.of("DRILL-001")))
                .thenReturn(List.of(PricingCacheEntry.from(pricing("DRILL-001", "125.50"))));

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hundred))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(hundredAndOne))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(service).getCachedBatch(List.of("DRILL-001"));
    }

    @Test
    void batchReusesExistingNotFoundAndMediaTypeResponses() throws Exception {
        when(service.getCachedBatch(List.of("MISSING"))).thenThrow(new PricingNotFoundException("MISSING"));

        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"MISSING\"]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICING_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/pricing/batch/cached"));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("[\"DRILL-001\"]"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mockMvc.perform(post("/api/v1/pricing/batch/cached")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("[\"DRILL-001\"]"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    private Pricing pricing(String serialNumber, String price) {
        return new Pricing(
                serialNumber,
                new BigDecimal(price),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }
}
