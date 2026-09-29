package com.rentflow.converter;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.rentflow.dto.PricingDTO;
import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;

import static org.assertj.core.api.Assertions.assertThat;

class PricingConverterTest {

    private final PricingConverter converter = new PricingConverter();

    @Test
    void mapsEveryBusinessFieldInBothDirections() {
        PricingDTO dto = new PricingDTO(
                "DRILL-001",
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));

        Pricing pricing = converter.toModel(dto);

        assertThat(pricing.getSerialNumber()).isEqualTo("DRILL-001");
        assertThat(pricing.getPrice()).isEqualByComparingTo("125.50");
        assertThat(pricing.getWeekendRate()).isEqualByComparingTo("1.2500");
        assertThat(pricing.getLongRentalCondition()).isEqualTo(7);
        assertThat(pricing.getLongRentalDiscount()).isEqualByComparingTo("0.1000");
        assertThat(pricing.getDeposit()).isEqualByComparingTo("300.00");
        assertThat(converter.toResponse(pricing)).isEqualTo(dto);
    }

    @Test
    void mapsCacheEntryDirectlyToTheSameResponse() {
        Pricing pricing = new Pricing(
                "DRILL-001",
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));

        PricingDTO response = converter.toResponse(PricingCacheEntry.from(pricing));

        assertThat(response).isEqualTo(converter.toResponse(pricing));
        assertThat(response.serialNumber()).isEqualTo("DRILL-001");
        assertThat(response.price()).isEqualByComparingTo("125.50");
        assertThat(response.weekendRate()).isEqualByComparingTo("1.2500");
        assertThat(response.longRentalCondition()).isEqualTo(7);
        assertThat(response.longRentalDiscount()).isEqualByComparingTo("0.1000");
        assertThat(response.deposit()).isEqualByComparingTo("300.00");
    }
}
