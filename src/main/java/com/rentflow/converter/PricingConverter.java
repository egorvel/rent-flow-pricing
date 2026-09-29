package com.rentflow.converter;

import org.springframework.stereotype.Component;

import com.rentflow.dto.PricingDTO;
import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;

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

    public PricingDTO toResponse(PricingCacheEntry entry) {
        return new PricingDTO(
                entry.serialNumber(),
                entry.price(),
                entry.weekendRate(),
                entry.longRentalCondition(),
                entry.longRentalDiscount(),
                entry.deposit());
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
