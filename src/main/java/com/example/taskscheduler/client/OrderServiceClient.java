package com.example.taskscheduler.client;

import com.example.taskscheduler.client.ClientModels.OrderCancelRequest;
import com.example.taskscheduler.client.ClientModels.OrderCancelResponse;
import com.example.taskscheduler.exception.ExternalServiceException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Client for Order Service API.
 * <p>
 * Uses:
 * - Resilience4j Circuit Breaker for fault tolerance
 * - Retry with exponential backoff
 * - WebClient for non-blocking HTTP calls
 */
@Slf4j
@Component
public class OrderServiceClient {

    private final WebClient webClient;

    public OrderServiceClient(@Qualifier("orderServiceWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Cancel an order
     *
     * @param request The cancellation request
     * @return The cancellation response
     * @throws ExternalServiceException if the API call fails
     */
    @CircuitBreaker(name = "orderService", fallbackMethod = "cancelOrderFallback")
    @Retry(name = "orderService")
    public OrderCancelResponse cancelOrder(OrderCancelRequest request) {
        log.info("Calling Order Service to cancel order: {}", request.getOrderId());

        try {
            return webClient.post()
                    .uri("/api/v1/orders/{orderId}/cancel", request.getOrderId())
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new ExternalServiceException("Order Service", response.statusCode().value(), body))))
                    .bodyToMono(OrderCancelResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to cancel order {}: {}", request.getOrderId(), e.getMessage());
            throw new ExternalServiceException("Order Service", e);
        }
    }

    /**
     * Fallback invoked when the call cannot complete - circuit open, retries
     * exhausted, or the underlying call threw. The CB-open case is detected
     * via {@link CallNotPermittedException}; anything else preserves the
     * underlying cause's category so the metric tag reflects the actual
     * failure mode.
     */
    @SuppressWarnings("unused")
    private OrderCancelResponse cancelOrderFallback(OrderCancelRequest request, Exception e) {
        if (e instanceof CallNotPermittedException) {
            log.warn("Order Service circuit OPEN, dropping cancel for order: {}", request.getOrderId());
            throw new ExternalServiceException("Order Service",
                    "Service temporarily unavailable (circuit breaker open)", e, ErrorCategory.CB_OPEN);
        }
        if (e instanceof ExternalServiceException ese) {
            // Already categorized at the source - propagate.
            throw ese;
        }
        log.warn("Order Service fallback (uncategorized): order={}, error={}", request.getOrderId(), e.getMessage());
        throw new ExternalServiceException("Order Service", e);
    }

    /**
     * Get order status (for validation before cancel)
     */
    @CircuitBreaker(name = "orderService")
    @Retry(name = "orderService")
    public Map<String, Object> getOrderStatus(String orderId) {
        log.debug("Getting order status for: {}", orderId);

        try {
            return webClient.get()
                    .uri("/api/v1/orders/{orderId}/status", orderId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new ExternalServiceException("Order Service", response.statusCode().value(), body))))
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get order status {}: {}", orderId, e.getMessage());
            throw new ExternalServiceException("Order Service", e);
        }
    }
}
