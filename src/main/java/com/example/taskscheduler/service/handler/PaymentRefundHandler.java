package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.client.ClientModels.PaymentRefundRequest;
import com.example.taskscheduler.client.PaymentServiceClient;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handler for PAYMENT_REFUND tasks.
 * <p>
 * Calls the Payment Service API to process a full refund.
 * <p>
 * Expected payload:
 * - amount: Refund amount (optional, full refund if not specified)
 * - currency: Currency code (default: USD)
 * - reason: Refund reason
 * - requestedBy: Who initiated the refund
 * <p>
 * Expected referenceId: Payment ID
 * Expected secondaryReferenceId: Transaction ID (optional)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundHandler implements TaskHandler {

    private final PaymentServiceClient paymentServiceClient;

    @Override
    public TaskType getTaskType() {
        return TaskType.PAYMENT_REFUND;
    }

    @Override
    public void validate(ScheduledTask task) {
        TaskHandler.super.validate(task);

        if (task.getReferenceId() == null || task.getReferenceId().isBlank()) {
            throw new IllegalArgumentException("Payment ID (referenceId) is required");
        }
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        var paymentId = task.getReferenceId();
        var transactionId = task.getSecondaryReferenceId();
        log.info("Executing PAYMENT_REFUND for payment: {}", paymentId);

        try {
            // Build refund request from task data
            var request = PaymentRefundRequest.builder()
                    .paymentId(paymentId)
                    .transactionId(transactionId)
                    .amount(getPayloadBigDecimal(task, "amount"))
                    .currency(getPayloadString(task, "currency", "USD"))
                    .reason(getPayloadString(task, "reason", "Automated refund"))
                    .requestedBy(getPayloadString(task, "requestedBy", "task-scheduler"))
                    .metadata(task.getMetadata())
                    .build();

            // Call Payment Service
            var response = paymentServiceClient.refundPayment(request);

            // Check response
            if (response != null && isSuccessStatus(response.getStatus())) {
                log.info("Successfully processed refund for payment: {}, refund ID: {}", paymentId, response.getRefundId());
                return TaskExecutionResult.success(Map.of(
                        "refundId", response.getRefundId() != null ? response.getRefundId() : "",
                        "paymentId", response.getPaymentId(),
                        "status", response.getStatus(),
                        "amount", response.getAmount() != null ? response.getAmount().toString() : "",
                        "message", response.getMessage() != null ? response.getMessage() : "Refund processed",
                        "processedAt", response.getProcessedAt() != null ? response.getProcessedAt() : ""
                ));
            } else {
                var status = response != null ? response.getStatus() : "null";
                var message = response != null ? response.getMessage() : "No response";
                var formatted = TaskExecutionResult.unexpectedStatusMessage(getTaskType(), status, message);
                log.warn("{} (reference={})", formatted, paymentId);
                return TaskExecutionResult.unexpectedStatus(getTaskType(), status, message);
            }
        } catch (ExternalServiceException e) {
            log.error("Payment Service error for payment {}: {}", paymentId, e.getMessage());

            if (e.getHttpStatusCode() != null) {
                int statusCode = e.getHttpStatusCode();

                if (statusCode == 404) {
                    return TaskExecutionResult.permanentFailure(
                            "Payment not found: " + paymentId,
                            "PAYMENT_NOT_FOUND"
                    );
                } else if (statusCode == 409) {
                    // Already refunded or invalid state
                    return TaskExecutionResult.permanentFailure(
                            "Payment cannot be refunded (conflict): " + e.getResponseBody(),
                            "PAYMENT_STATE_CONFLICT"
                    );
                } else if (statusCode == 400) {
                    return TaskExecutionResult.permanentFailure(
                            "Invalid refund request: " + e.getResponseBody(),
                            "VALIDATION_ERROR"
                    );
                } else if (statusCode == 422) {
                    // Unprocessable - insufficient funds or business rule violation
                    return TaskExecutionResult.permanentFailure(
                            "Refund cannot be processed: " + e.getResponseBody(),
                            "BUSINESS_RULE_VIOLATION"
                    );
                }

                return TaskExecutionResult.httpFailure(statusCode, e.getMessage());
            }

            // No HTTP status: use the categorized cause (TIMEOUT, CONNECT_REFUSED, CB_OPEN, ...)
            return TaskExecutionResult.failure(e.getMessage(), e.getCategory().name());
        } catch (Exception e) {
            log.error("Unexpected error processing refund for payment {}: {}", paymentId, e.getMessage(), e);
            return TaskExecutionResult.failure(e);
        }
    }

    @Override
    public long calculateNextRetryDelayMs(ScheduledTask task, int defaultDelayHours) {
        // Payment refunds might need more careful retry strategy
        var customDelayHours = task.getMetadataValue("retryDelayHours", Integer.class);
        if (customDelayHours != null) {
            return addJitter(customDelayHours * 60L * 60L * 1000L);
        }

        // For payment operations, be more conservative
        // Wait longer between retries to avoid duplicate refunds
        var retryCount = task.getRetryCount();
        if (retryCount == 0) {
            // First retry after 2 hours
            return addJitter(2L * 60L * 60L * 1000L);
        } else if (retryCount < 3) {
            // 6 hours, then 12 hours
            return addJitter((3L + retryCount * 3L) * 60L * 60L * 1000L);
        }

        // After 3 retries, switch to daily retries
        return addJitter(defaultDelayHours * 60L * 60L * 1000L);
    }

    private long addJitter(long baseDelayMs) {
        long jitter = ThreadLocalRandom.current().nextLong(baseDelayMs / 10, baseDelayMs / 4 + 1);
        return baseDelayMs + jitter;
    }

    private boolean isSuccessStatus(String status) {
        return status != null &&
                (status.equalsIgnoreCase("COMPLETED") ||
                        status.equalsIgnoreCase("SUCCESS") ||
                        status.equalsIgnoreCase("REFUNDED") ||
                        status.equalsIgnoreCase("PROCESSED"));
    }

    private String getPayloadString(ScheduledTask task, String key, String defaultValue) {
        if (task.getPayload() == null) {
            return defaultValue;
        }
        var value = task.getPayload().get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private BigDecimal getPayloadBigDecimal(ScheduledTask task, String key) {
        if (task.getPayload() == null) {
            return null;
        }
        var value = task.getPayload().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
