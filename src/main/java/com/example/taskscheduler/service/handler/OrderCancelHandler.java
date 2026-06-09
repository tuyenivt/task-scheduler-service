package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.client.ClientModels.OrderCancelRequest;
import com.example.taskscheduler.client.OrderServiceClient;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handler for ORDER_CANCEL tasks.
 * <p>
 * Calls the Order Service API to cancel an order.
 * <p>
 * Expected payload:
 * - reason: Cancellation reason
 * - cancelledBy: Who initiated the cancellation
 * <p>
 * Expected metadata (optional):
 * - notifyCustomer: Whether to send customer notification
 * - refundRequired: Whether a refund should be triggered
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelHandler implements TaskHandler {

    private final OrderServiceClient orderServiceClient;
    private final RetryDelayCalculator retryDelayCalculator;

    @Override
    public TaskType getTaskType() {
        return TaskType.ORDER_CANCEL;
    }

    @Override
    public void validate(ScheduledTask task) {
        TaskHandler.super.validate(task);

        // Order ID is required
        if (task.getReferenceId() == null || task.getReferenceId().isBlank()) {
            throw new IllegalArgumentException("Order ID (referenceId) is required");
        }
    }

    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        var orderId = task.getReferenceId();
        log.info("Executing ORDER_CANCEL for order: {}", orderId);

        try {
            // Build cancellation request from task data
            var request = OrderCancelRequest.builder()
                    .orderId(orderId)
                    .reason(getPayloadString(task, "reason", "Automated cancellation"))
                    .cancelledBy(getPayloadString(task, "cancelledBy", "task-scheduler"))
                    .metadata(task.getMetadata())
                    .build();

            // Call Order Service
            var response = orderServiceClient.cancelOrder(request);

            // Check response
            if (response != null && "CANCELLED".equalsIgnoreCase(response.getStatus())) {
                log.info("Successfully cancelled order: {}", orderId);
                return TaskExecutionResult.success(Map.of(
                        "orderId", response.getOrderId(),
                        "status", response.getStatus(),
                        "message", response.getMessage() != null ? response.getMessage() : "Order cancelled",
                        "cancelledAt", response.getCancelledAt() != null ? response.getCancelledAt() : ""
                ));
            } else {
                var status = response != null ? response.getStatus() : "null";
                var message = response != null ? response.getMessage() : "No response";
                var formatted = TaskExecutionResult.unexpectedStatusMessage(getTaskType(), status, message);
                log.warn("{} (reference={})", formatted, orderId);
                return TaskExecutionResult.unexpectedStatus(getTaskType(), status, message);
            }
        } catch (ExternalServiceException e) {
            log.error("Order Service error for order {}: {}", orderId, e.getMessage());

            if (e.getHttpStatusCode() != null) {
                // Check for specific HTTP error codes
                int statusCode = e.getHttpStatusCode();

                if (statusCode == 404) {
                    // Order not found - permanent failure, don't retry
                    return TaskExecutionResult.permanentFailure(
                            "Order not found: " + orderId,
                            "ORDER_NOT_FOUND"
                    );
                } else if (statusCode == 409) {
                    // Order already canceled or in invalid state
                    return TaskExecutionResult.permanentFailure(
                            "Order cannot be cancelled (conflict): " + e.getResponseBody(),
                            "ORDER_STATE_CONFLICT"
                    );
                } else if (statusCode == 400) {
                    // Bad request - likely validation error, don't retry
                    return TaskExecutionResult.permanentFailure(
                            "Invalid cancellation request: " + e.getResponseBody(),
                            "VALIDATION_ERROR"
                    );
                }

                return TaskExecutionResult.httpFailure(statusCode, e.getMessage());
            }

            // No HTTP status: use the categorized cause (TIMEOUT, CONNECT_REFUSED, CB_OPEN, ...)
            return TaskExecutionResult.failure(e.getMessage(), e.getCategory().name());
        } catch (Exception e) {
            log.error("Unexpected error cancelling order {}: {}", orderId, e.getMessage(), e);
            return TaskExecutionResult.failure(e);
        }
    }

    @Override
    public long calculateNextRetryDelayMs(ScheduledTask task, int defaultDelayHours) {
        // Check if metadata specifies custom retry behavior
        var customDelayHours = task.getMetadataValue("retryDelayHours", Integer.class);
        if (customDelayHours != null) {
            return retryDelayCalculator.addJitter(customDelayHours * 60L * 60L * 1000L);
        }

        // Use exponential backoff for the first few retries, then default to next day
        var retryCount = task.getRetryCount();
        if (retryCount < 3) {
            // 1 hour, 2 hours, 4 hours for first 3 retries
            return retryDelayCalculator.addJitter((long) Math.pow(2, retryCount) * 60L * 60L * 1000L);
        }

        // After 3 retries, switch to daily retries
        return retryDelayCalculator.addJitter(defaultDelayHours * 60L * 60L * 1000L);
    }

    private String getPayloadString(ScheduledTask task, String key, String defaultValue) {
        if (task.getPayload() == null) {
            return defaultValue;
        }
        var value = task.getPayload().get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
