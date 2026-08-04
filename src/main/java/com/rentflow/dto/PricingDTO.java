package com.rentflow.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Complete pricing representation used for creation and replacement.", example = """
                {
                  "serialNumber": "DRILL-001",
                  "price": 125.50,
                  "weekendRate": 1.2500,
                  "longRentalCondition": 7,
                  "longRentalDiscount": 0.1000,
                  "deposit": 300.00
                }
                """)
public record PricingDTO(
        @Schema(
                description = "Unique, manually assigned, immutable equipment serial number.",
                example = "DRILL-001",
                minLength = 1,
                maxLength = 64,
                pattern = SERIAL_NUMBER_PATTERN,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "must not be blank") @Pattern(
                regexp = SERIAL_NUMBER_PATTERN,
                message =
                        "must start with an alphanumeric character and contain only alphanumeric characters, dots, underscores, or hyphens")
        String serialNumber,

        @Schema(
                description = "Positive currency-agnostic daily price with at most two fractional digits.",
                example = "125.50",
                minimum = "0",
                exclusiveMinimum = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 fractional digits") @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0") BigDecimal price,

        @Schema(
                description = "Positive multiplier applied to the daily price on weekend rental days.",
                example = "1.2500",
                minimum = "0",
                exclusiveMinimum = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @Digits(integer = 15, fraction = 4, message = "must have at most 15 integer digits and 4 fractional digits") @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0") BigDecimal weekendRate,

        @Schema(
                description = "Calendar-day duration at which the long-rental discount qualifies.",
                example = "7",
                minimum = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @Min(value = 1, message = "must be at least 1") Integer longRentalCondition,

        @Schema(
                description = "Fractional long-rental discount; 0.1000 means ten percent.",
                example = "0.1000",
                minimum = "0",
                maximum = "1",
                exclusiveMaximumValue = 1,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @Digits(integer = 1, fraction = 4, message = "must have at most 1 integer digit and 4 fractional digits") @DecimalMin(value = "0", message = "must be greater than or equal to 0") @DecimalMax(value = "1", inclusive = false, message = "must be less than 1") BigDecimal longRentalDiscount,

        @Schema(
                description = "Non-negative currency-agnostic security deposit with at most two fractional digits.",
                example = "300.00",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "must not be null") @Digits(integer = 17, fraction = 2, message = "must have at most 17 integer digits and 2 fractional digits") @DecimalMin(value = "0", message = "must be greater than or equal to 0") BigDecimal deposit) {

    public static final String SERIAL_NUMBER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
}
