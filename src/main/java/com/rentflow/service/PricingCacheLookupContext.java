package com.rentflow.service;

import java.util.function.Supplier;

import org.springframework.data.redis.serializer.SerializationException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;

public final class PricingCacheLookupContext extends Observation.Context {
    private String readFailureReason = "miss";
    private boolean loaded;
    private boolean degraded;

    public static <T> T observe(ObservationRegistry registry, String mode, Supplier<T> lookup) {
        PricingCacheLookupContext context = new PricingCacheLookupContext();
        Observation observation = Observation.createNotStarted("pricing.cache.lookup", () -> context, registry)
                .lowCardinalityKeyValue("mode", mode)
                .start();
        String outcome = "error";
        try (Observation.Scope ignored = observation.openScope()) {
            T result = lookup.get();
            outcome = context.degraded ? "degraded" : context.loaded ? "miss" : "hit";
            return result;
        } catch (PricingNotFoundException exception) {
            outcome = "not_found";
            throw exception;
        } catch (RuntimeException | Error exception) {
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome);
            observation.stop();
        }
    }

    public static String readReason(ObservationRegistry registry) {
        PricingCacheLookupContext context = current(registry);
        return context == null ? "miss" : context.readFailureReason;
    }

    public static void loaded(ObservationRegistry registry) {
        PricingCacheLookupContext context = current(registry);
        if (context != null) {
            context.loaded = true;
        }
    }

    public static void failed(ObservationRegistry registry, String operation, RuntimeException exception) {
        PricingCacheLookupContext context = current(registry);
        if (context != null) {
            context.degraded = true;
            if (operation.equals("get")) {
                context.readFailureReason = "read_error";
                for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SerializationException) {
                        context.readFailureReason = "decode_error";
                        break;
                    }
                }
            }
        }
    }

    private static PricingCacheLookupContext current(ObservationRegistry registry) {
        ObservationView observation = registry.getCurrentObservation();
        while (observation != null) {
            if (observation.getContextView() instanceof PricingCacheLookupContext context) {
                return context;
            }
            observation = observation.getContextView().getParentObservation();
        }
        return null;
    }
}
