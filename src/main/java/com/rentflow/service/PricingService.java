package com.rentflow.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentflow.model.Pricing;
import com.rentflow.model.PricingCacheEntry;
import com.rentflow.repository.PricingRepository;

@Service
public class PricingService {

    private final PricingRepository repository;
    private final InventoryGateway inventoryGateway;
    private final PricingCacheService cacheService;
    private final ApplicationEventPublisher eventPublisher;

    public PricingService(
            PricingRepository repository,
            InventoryGateway inventoryGateway,
            PricingCacheService cacheService,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.inventoryGateway = inventoryGateway;
        this.cacheService = cacheService;
        this.eventPublisher = eventPublisher;
    }

    public Pricing create(Pricing pricing) {
        if (repository.existsById(pricing.getSerialNumber())) {
            throw new PricingAlreadyExistsException(pricing.getSerialNumber());
        }
        if (!inventoryGateway.exists(pricing.getSerialNumber())) {
            throw new InventoryItemNotFoundException(pricing.getSerialNumber());
        }
        try {
            Pricing created = repository.saveAndFlush(pricing);
            publishChange(created.getSerialNumber());
            return created;
        } catch (DataIntegrityViolationException exception) {
            throw new PricingAlreadyExistsException(pricing.getSerialNumber());
        }
    }

    @Transactional(readOnly = true)
    public Pricing get(String serialNumber) {
        return repository.findById(serialNumber).orElseThrow(() -> new PricingNotFoundException(serialNumber));
    }

    public PricingCacheEntry getCached(String serialNumber) {
        return cacheService.get(serialNumber);
    }

    @Transactional(readOnly = true)
    public Page<Pricing> list(int page, int size, PricingSortField sortField, Sort.Direction direction) {
        List<Sort.Order> orders = new ArrayList<>();
        orders.add(new Sort.Order(direction, sortField.property()));
        if (sortField != PricingSortField.SERIAL_NUMBER) {
            orders.add(Sort.Order.asc("serialNumber"));
        }
        return repository.findAll(PageRequest.of(page, size, Sort.by(orders)));
    }

    @Transactional
    public Pricing replace(Pricing replacement) {
        Pricing pricing = repository
                .findById(replacement.getSerialNumber())
                .orElseThrow(() -> new PricingNotFoundException(replacement.getSerialNumber()));
        pricing.replaceDetails(replacement);
        publishChange(pricing.getSerialNumber());
        return pricing;
    }

    @Transactional
    public void delete(String serialNumber) {
        Pricing pricing =
                repository.findById(serialNumber).orElseThrow(() -> new PricingNotFoundException(serialNumber));
        repository.delete(pricing);
        publishChange(serialNumber);
    }

    private void publishChange(String serialNumber) {
        eventPublisher.publishEvent(new PricingChangedEvent(serialNumber));
    }
}
