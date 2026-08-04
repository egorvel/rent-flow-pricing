package com.rentflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rentflow.model.Pricing;

public interface PricingRepository extends JpaRepository<Pricing, String> {}
