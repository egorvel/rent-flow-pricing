package com.rentflow;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentflow.model.Pricing;
import com.rentflow.repository.PricingRepository;
import com.rentflow.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PricingRepository repository;

    @Autowired
    private Flyway flyway;

    @Test
    void runsAsRestrictedPricingRoleInRentflowDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo(PRICING_USERNAME);
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("rentflow");
    }

    @Test
    void createsOnlyPricingObjectsAndRecordsMigrationOnce() throws Exception {
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'pricing'
                          AND table_name = 'pricing_entries'
                        """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pricing.flyway_schema_history WHERE version = '1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'pricing'
                          AND tablename = 'pricing_entries'
                        """, String.class)).containsExactly("pricing_entries_pkey");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('pricing_entries', 'flyway_schema_history')
                        """, Integer.class)).isZero();

        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement =
                        connection.prepareStatement("SELECT marker FROM rental.sentinel WHERE id = 1");
                ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("marker")).isEqualTo("untouched");
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pricing.flyway_schema_history WHERE version = '1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseConstraintsRejectInvalidRows() {
        assertInvalidRow("/INVALID", "125.50", "1.2500", 7, "0.1000", "300.00");
        assertInvalidRow("DRILL-001", "0.00", "1.2500", 7, "0.1000", "300.00");
        assertInvalidRow("DRILL-001", "125.50", "0.0000", 7, "0.1000", "300.00");
        assertInvalidRow("DRILL-001", "125.50", "1.2500", 0, "0.1000", "300.00");
        assertInvalidRow("DRILL-001", "125.50", "1.2500", 7, "-0.0001", "300.00");
        assertInvalidRow("DRILL-001", "125.50", "1.2500", 7, "1.0000", "300.00");
        assertInvalidRow("DRILL-001", "125.50", "1.2500", 7, "0.1000", "-0.01");
    }

    @Test
    void committedRowsSurviveASecondApplicationContext() {
        repository.saveAndFlush(pricing("DRILL-RESTART"));

        try (ConfigurableApplicationContext secondContext = startApplication(Map.of())) {
            PricingRepository secondRepository = secondContext.getBean(PricingRepository.class);
            assertThat(secondRepository.findById("DRILL-RESTART")).isPresent();
        }
    }

    @Test
    void startupFailsWhenTheOwnedSchemaIsMissing() {
        assertThatThrownBy(() -> {
                    try (ConfigurableApplicationContext ignored = startApplication(Map.of(
                            "spring.flyway.default-schema", "missing_pricing",
                            "spring.flyway.schemas", "missing_pricing",
                            "spring.jpa.properties.hibernate.default_schema", "missing_pricing"))) {
                        // Startup must fail before a context can be used.
                    }
                })
                .hasStackTraceContaining("missing_pricing");
    }

    private void assertInvalidRow(
            String serialNumber,
            String price,
            String weekendRate,
            int longRentalCondition,
            String longRentalDiscount,
            String deposit) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO pricing.pricing_entries (
                            serial_number,
                            price,
                            weekend_rate,
                            long_rental_condition,
                            long_rental_discount,
                            deposit
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        serialNumber,
                        new BigDecimal(price),
                        new BigDecimal(weekendRate),
                        longRentalCondition,
                        new BigDecimal(longRentalDiscount),
                        new BigDecimal(deposit)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Pricing pricing(String serialNumber) {
        return new Pricing(
                serialNumber,
                new BigDecimal("125.50"),
                new BigDecimal("1.2500"),
                7,
                new BigDecimal("0.1000"),
                new BigDecimal("300.00"));
    }

    private ConfigurableApplicationContext startApplication(Map<String, Object> overrides) {
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", POSTGRES.getJdbcUrl());
        properties.put("spring.datasource.username", PRICING_USERNAME);
        properties.put("spring.datasource.password", PRICING_PASSWORD);
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.putAll(overrides);
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        return new SpringApplicationBuilder(PricingApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments);
    }
}
