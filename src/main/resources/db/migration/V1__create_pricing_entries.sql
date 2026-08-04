CREATE TABLE pricing.pricing_entries (
    serial_number varchar(64) PRIMARY KEY,
    price numeric(19, 2) NOT NULL,
    weekend_rate numeric(19, 4) NOT NULL,
    long_rental_condition integer NOT NULL,
    long_rental_discount numeric(5, 4) NOT NULL,
    deposit numeric(19, 2) NOT NULL,
    CONSTRAINT chk_pricing_serial_number
        CHECK (serial_number ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    CONSTRAINT chk_pricing_price CHECK (price > 0),
    CONSTRAINT chk_pricing_weekend_rate CHECK (weekend_rate > 0),
    CONSTRAINT chk_pricing_long_rental_condition CHECK (long_rental_condition >= 1),
    CONSTRAINT chk_pricing_long_rental_discount
        CHECK (long_rental_discount >= 0 AND long_rental_discount < 1),
    CONSTRAINT chk_pricing_deposit CHECK (deposit >= 0)
);
