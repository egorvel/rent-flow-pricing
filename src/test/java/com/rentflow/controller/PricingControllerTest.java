package com.rentflow.controller;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
