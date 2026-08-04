package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.PricingDTO;
import com.rentflow.model.Pricing;

@Component
public class PricingConverter {

    public PricingDTO toResponse(Pricing pricing) {
        return new PricingDTO(
                pricing.getSerialNumber(),
                pricing.getPrice(),
                pricing.getWeekendRate(),
                pricing.getLongRentalCondition(),
                pricing.getLongRentalDiscount(),
                pricing.getDeposit());
    }

    public Pricing toModel(PricingDTO dto) {
        return new Pricing(
                dto.serialNumber(),
                dto.price(),
                dto.weekendRate(),
                dto.longRentalCondition(),
                dto.longRentalDiscount(),
                dto.deposit());
    }
}
