package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.client.ClientModels.PaymentVoidRequest;
import com.example.taskscheduler.client.PaymentServiceClient;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handler for PAYMENT_VOID tasks.
 * <p>
 * Voids a pending payment authorization.
 * <p>
 * Expected referenceId: Payment ID
 * Expected secondaryReferenceId: Authorization ID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentVoidHandler implements TaskHandler {

    private final PaymentServiceClient paymentServiceClient;

    @Override
    public TaskType getTaskType() {
        return TaskType.PAYMENT_VOID;
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
        var authorizationId = task.getSecondaryReferenceId();
        log.info("Executing PAYMENT_VOID for payment: {}", paymentId);

        try {
            var request = PaymentVoidRequest.builder()
                    .paymentId(paymentId)
                    .authorizationId(authorizationId)
                    .reason(getPayloadString(task, "reason", "Automated void"))
                    .requestedBy(getPayloadString(task, "requestedBy", "task-scheduler"))
                    .build();

            var response = paymentServiceClient.voidPayment(request);

            if (response != null && isSuccessStatus(response.getStatus())) {
                log.info("Successfully voided payment: {}", paymentId);
                return TaskExecutionResult.success(Map.of(
                        "paymentId", response.getPaymentId(),
                        "status", response.getStatus(),
                        "message", response.getMessage() != null ? response.getMessage() : "Payment voided",
                        "voidedAt", response.getVoidedAt() != null ? response.getVoidedAt() : ""
                ));
            } else {
                var status = response != null ? response.getStatus() : "null";
                var message = response != null ? response.getMessage() : "No response";
                var formatted = TaskExecutionResult.unexpectedStatusMessage(getTaskType(), status, message);
                log.warn("{} (reference={})", formatted, paymentId);
                return TaskExecutionResult.unexpectedStatus(getTaskType(), status, message);
            }
        } catch (ExternalServiceException e) {
            log.error("Payment Service error for void {}: {}", paymentId, e.getMessage());

            if (e.getHttpStatusCode() != null) {
                int statusCode = e.getHttpStatusCode();

                if (statusCode == 404) {
                    return TaskExecutionResult.permanentFailure(
                            "Payment not found: " + paymentId,
                            "PAYMENT_NOT_FOUND"
                    );
                } else if (statusCode == 409) {
                    return TaskExecutionResult.permanentFailure(
                            "Payment cannot be voided (conflict): " + e.getResponseBody(),
                            "PAYMENT_STATE_CONFLICT"
                    );
                } else if (statusCode == 400) {
                    return TaskExecutionResult.permanentFailure(
                            "Invalid void request: " + e.getResponseBody(),
                            "VALIDATION_ERROR"
                    );
                }

                return TaskExecutionResult.httpFailure(statusCode, e.getMessage());
            }

            // No HTTP status: use the categorized cause (TIMEOUT, CONNECT_REFUSED, CB_OPEN, ...)
            return TaskExecutionResult.failure(e.getMessage(), e.getCategory().name());
        } catch (Exception e) {
            log.error("Unexpected error voiding payment {}: {}", paymentId, e.getMessage(), e);
            return TaskExecutionResult.failure(e);
        }
    }

    private boolean isSuccessStatus(String status) {
        return status != null &&
                (status.equalsIgnoreCase("VOIDED") ||
                        status.equalsIgnoreCase("SUCCESS") ||
                        status.equalsIgnoreCase("COMPLETED"));
    }

    private String getPayloadString(ScheduledTask task, String key, String defaultValue) {
        if (task.getPayload() == null) {
            return defaultValue;
        }
        var value = task.getPayload().get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
