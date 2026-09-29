package com.rentflow.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class PricingCacheInvalidationService {

    @CacheEvict(cacheNames = PricingCacheService.CACHE_NAME, key = "#event.serialNumber()")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void invalidate(PricingChangedEvent event) {}
}
