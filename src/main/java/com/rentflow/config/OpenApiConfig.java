package com.rentflow.config;

import java.math.BigDecimal;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.media.Schema;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info =
                @Info(
                        title = "RentFlow Pricing API",
                        version = "v1",
                        description = "CRUD API for RentFlow equipment pricing terms."),
        tags = @Tag(name = "Pricing", description = "Equipment pricing operations."))
public class OpenApiConfig {

    @Bean
    OpenApiCustomizer pricingNumericBoundsCustomizer() {
        return openApi -> {
            Schema<?> pricingSchema = openApi.getComponents().getSchemas().get("PricingDTO");
            Schema<?> priceSchema = pricingSchema.getProperties().get("price");
            Schema<?> weekendRateSchema = pricingSchema.getProperties().get("weekendRate");

            priceSchema.setExclusiveMinimumValue(BigDecimal.ZERO);
            weekendRateSchema.setExclusiveMinimumValue(BigDecimal.ZERO);
        };
    }
}
