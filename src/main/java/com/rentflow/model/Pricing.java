package com.rentflow.model;

import java.math.BigDecimal;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pricing_entries", schema = "pricing")
public class Pricing {

    @Id
    @Column(name = "serial_number", nullable = false, updatable = false, length = 64)
    private String serialNumber;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "weekend_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal weekendRate;

    @Column(name = "long_rental_condition", nullable = false)
    private Integer longRentalCondition;

    @Column(name = "long_rental_discount", nullable = false, precision = 5, scale = 4)
    private BigDecimal longRentalDiscount;

    @Column(name = "deposit", nullable = false, precision = 19, scale = 2)
    private BigDecimal deposit;

    protected Pricing() {}

    public Pricing(
            String serialNumber,
            BigDecimal price,
            BigDecimal weekendRate,
            Integer longRentalCondition,
            BigDecimal longRentalDiscount,
            BigDecimal deposit) {
        this.serialNumber = Objects.requireNonNull(serialNumber, "serialNumber must not be null");
        replaceDetails(price, weekendRate, longRentalCondition, longRentalDiscount, deposit);
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getWeekendRate() {
        return weekendRate;
    }

    public Integer getLongRentalCondition() {
        return longRentalCondition;
    }

    public BigDecimal getLongRentalDiscount() {
        return longRentalDiscount;
    }

    public BigDecimal getDeposit() {
        return deposit;
    }

    public void replaceDetails(Pricing pricing) {
        Objects.requireNonNull(pricing, "pricing must not be null");
        replaceDetails(
                pricing.getPrice(),
                pricing.getWeekendRate(),
                pricing.getLongRentalCondition(),
                pricing.getLongRentalDiscount(),
                pricing.getDeposit());
    }

    public void replaceDetails(
            BigDecimal price,
            BigDecimal weekendRate,
            Integer longRentalCondition,
            BigDecimal longRentalDiscount,
            BigDecimal deposit) {
        this.price = Objects.requireNonNull(price, "price must not be null");
        this.weekendRate = Objects.requireNonNull(weekendRate, "weekendRate must not be null");
        this.longRentalCondition = Objects.requireNonNull(longRentalCondition, "longRentalCondition must not be null");
        this.longRentalDiscount = Objects.requireNonNull(longRentalDiscount, "longRentalDiscount must not be null");
        this.deposit = Objects.requireNonNull(deposit, "deposit must not be null");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Pricing pricing)) {
            return false;
        }
        return serialNumber.equals(pricing.serialNumber);
    }

    @Override
    public int hashCode() {
        return serialNumber.hashCode();
    }
}
