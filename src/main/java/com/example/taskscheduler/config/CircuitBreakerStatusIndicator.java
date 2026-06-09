package com.example.taskscheduler.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * Aggregated circuit-breaker status visible at
 * {@code /actuator/health/components/circuitBreakerStatus}.
 * <p>
 * Reports the state of every registered Resilience4j circuit breaker plus the
 * failure rate. Always returns {@code UP} - a single open breaker should not
 * mark the whole instance DOWN (that would let k8s evict pods because Order
 * Service is degraded). Operators read the details to see <em>which</em>
 * downstream is broken without paging on per-CB indicators individually.
 */
@Component("circuitBreakerStatus")
@RequiredArgsConstructor
public class CircuitBreakerStatusIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public Health health() {
        var builder = Health.up();
        var summary = new LinkedHashMap<String, Object>();

        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            var detail = new LinkedHashMap<String, Object>();
            detail.put("state", cb.getState().name());
            detail.put("failureRate", String.format("%.2f%%", cb.getMetrics().getFailureRate()));
            detail.put("bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            detail.put("failedCalls", cb.getMetrics().getNumberOfFailedCalls());
            summary.put(cb.getName(), detail);
        }

        builder.withDetail("circuitBreakers", summary);
        return builder.build();
    }
}
