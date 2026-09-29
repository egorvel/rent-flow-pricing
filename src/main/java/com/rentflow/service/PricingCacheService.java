package com.rentflow.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

@Service
public class PricingCacheService {

    public static final String CACHE_NAME = "pricingBySerial";

    private final PricingRepository repository;

    public PricingCacheService(PricingRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#serialNumber")
    @Transactional(readOnly = true)
    public PricingCacheEntry get(String serialNumber) {
        return repository
                .findById(serialNumber)
                .map(PricingCacheEntry::from)
                .orElseThrow(() -> new PricingNotFoundException(serialNumber));
    }
}
