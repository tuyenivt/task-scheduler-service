package com.example.taskscheduler.client;

import com.example.taskscheduler.client.ClientModels.PaymentRefundRequest;
import com.example.taskscheduler.client.ClientModels.PaymentRefundResponse;
import com.example.taskscheduler.client.ClientModels.PaymentVoidRequest;
import com.example.taskscheduler.client.ClientModels.PaymentVoidResponse;
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
 * Client for Payment Service API.
 * <p>
 * Uses:
 * - Resilience4j Circuit Breaker for fault tolerance
 * - Retry with exponential backoff
 * - WebClient for non-blocking HTTP calls
 */
@Slf4j
@Component
public class PaymentServiceClient {

    private final WebClient webClient;

    public PaymentServiceClient(@Qualifier("paymentServiceWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Process a full refund
     *
     * @param request The refund request
     * @return The refund response
     * @throws ExternalServiceException if the API call fails
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "refundPaymentFallback")
    @Retry(name = "paymentService")
    public PaymentRefundResponse refundPayment(PaymentRefundRequest request) {
        log.info("Calling Payment Service to refund payment: {}, amount: {}", request.getPaymentId(), request.getAmount());

        try {
            return webClient.post()
                    .uri("/api/v1/payments/{paymentId}/refund", request.getPaymentId())
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new ExternalServiceException("Payment Service", response.statusCode().value(), body))))
                    .bodyToMono(PaymentRefundResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to refund payment {}: {}", request.getPaymentId(), e.getMessage());
            throw new ExternalServiceException("Payment Service", e);
        }
    }

    /**
     * Fallback invoked when refund cannot complete - circuit open, retries
     * exhausted, or the underlying call threw. See {@link OrderServiceClient}
     * fallback for the categorization rationale.
     */
    @SuppressWarnings("unused")
    private PaymentRefundResponse refundPaymentFallback(PaymentRefundRequest request, Exception e) {
        if (e instanceof CallNotPermittedException) {
            log.warn("Payment Service circuit OPEN, dropping refund for payment: {}", request.getPaymentId());
            throw new ExternalServiceException("Payment Service",
                    "Service temporarily unavailable (circuit breaker open)", e, ErrorCategory.CB_OPEN);
        }
        if (e instanceof ExternalServiceException ese) {
            throw ese;
        }
        log.warn("Payment Service refund fallback (uncategorized): payment={}, error={}",
                request.getPaymentId(), e.getMessage());
        throw new ExternalServiceException("Payment Service", e);
    }

    /**
     * Void a pending payment authorization
     *
     * @param request The void request
     * @return The void response
     * @throws ExternalServiceException if the API call fails
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "voidPaymentFallback")
    @Retry(name = "paymentService")
    public PaymentVoidResponse voidPayment(PaymentVoidRequest request) {
        log.info("Calling Payment Service to void payment: {}", request.getPaymentId());

        try {
            return webClient.post()
                    .uri("/api/v1/payments/{paymentId}/void", request.getPaymentId())
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new ExternalServiceException("Payment Service", response.statusCode().value(), body))))
                    .bodyToMono(PaymentVoidResponse.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to void payment {}: {}", request.getPaymentId(), e.getMessage());
            throw new ExternalServiceException("Payment Service", e);
        }
    }

    /**
     * Fallback invoked when void cannot complete - see refund fallback above
     * for the categorization rationale.
     */
    @SuppressWarnings("unused")
    private PaymentVoidResponse voidPaymentFallback(PaymentVoidRequest request, Exception e) {
        if (e instanceof CallNotPermittedException) {
            log.warn("Payment Service circuit OPEN, dropping void for payment: {}", request.getPaymentId());
            throw new ExternalServiceException("Payment Service",
                    "Service temporarily unavailable (circuit breaker open)", e, ErrorCategory.CB_OPEN);
        }
        if (e instanceof ExternalServiceException ese) {
            throw ese;
        }
        log.warn("Payment Service void fallback (uncategorized): payment={}, error={}",
                request.getPaymentId(), e.getMessage());
        throw new ExternalServiceException("Payment Service", e);
    }

    /**
     * Get payment details (for validation before refund)
     */
    @CircuitBreaker(name = "paymentService")
    @Retry(name = "paymentService")
    public Map<String, Object> getPaymentDetails(String paymentId) {
        log.debug("Getting payment details for: {}", paymentId);

        try {
            return webClient.get()
                    .uri("/api/v1/payments/{paymentId}", paymentId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(
                                            new ExternalServiceException("Payment Service", response.statusCode().value(), body))))
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get payment details {}: {}", paymentId, e.getMessage());
            throw new ExternalServiceException("Payment Service", e);
        }
    }
}
