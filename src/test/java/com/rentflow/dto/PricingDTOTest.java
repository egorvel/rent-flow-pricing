package com.rentflow.dto;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingDTOTest {

    @Test
    void acceptsTheCompleteValidProfileIncludingZeroDiscountAndDeposit() {
        PricingDTO request = new PricingDTO(
                "DRILL_001.2",
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.0000"),
                new BigDecimal("0.00"));

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void rejectsEveryMissingField() {
        PricingDTO request = new PricingDTO(null, null, null, null, null, null);

        assertThat(violations(request))
                .containsExactlyInAnyOrder(
                        "serialNumber", "price", "weekendRate", "longRentalCondition", "longRentalDiscount", "deposit");
    }

    @Test
    void rejectsInvalidBounds() {
        PricingDTO request = new PricingDTO(
                " bad ",
                BigDecimal.ZERO,
                new BigDecimal("0.0000"),
                0,
                new BigDecimal("1.0000"),
                new BigDecimal("-0.01"));

        assertThat(violations(request))
                .contains(
                        "serialNumber", "price", "weekendRate", "longRentalCondition", "longRentalDiscount", "deposit");
    }

    @Test
    void rejectsExcessIntegerAndFractionalDigits() {
        PricingDTO request = new PricingDTO(
                "S".repeat(65),
                new BigDecimal("123456789012345678.00"),
                new BigDecimal("1234567890123456.0000"),
                7,
                new BigDecimal("0.12345"),
                new BigDecimal("123456789012345678.00"));

        assertThat(violations(request))
                .containsExactlyInAnyOrder("serialNumber", "price", "weekendRate", "longRentalDiscount", "deposit");
    }

    private Set<String> violations(PricingDTO request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(Collectors.toSet());
        }
    }
}
