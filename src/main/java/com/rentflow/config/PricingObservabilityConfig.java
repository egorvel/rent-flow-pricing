package com.rentflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;

@Configuration(proxyBeanMethods = false)
public class PricingObservabilityConfig {
    @Bean
    ObservationPredicate excludeManagementObservations() {
        return (_, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                String path = request.getCarrier().getRequestURI();
                return !path.equals("/actuator")
                        && !path.startsWith("/actuator/")
                        && !path.equals("/livez")
                        && !path.equals("/readyz");
            }
            return true;
        };
    }
}
