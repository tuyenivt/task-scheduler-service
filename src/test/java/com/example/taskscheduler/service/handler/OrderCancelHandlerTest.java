package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.client.ClientModels.OrderCancelResponse;
import com.example.taskscheduler.client.OrderServiceClient;
import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCancelHandler Tests")
class OrderCancelHandlerTest {

    @Mock
    private OrderServiceClient orderServiceClient;

    private OrderCancelHandler handler;

    @BeforeEach
    void setUp() {
        // Real RetryDelayCalculator with default jitter band (10-25%) so the
        // existing delay-range assertions in this test class continue to hold.
        handler = new OrderCancelHandler(orderServiceClient,
                new RetryDelayCalculator(new TaskSchedulerProperties()));
    }

    @Test
    @DisplayName("Should return ORDER_CANCEL task type")
    void shouldReturnCorrectTaskType() {
        assertThat(handler.getTaskType()).isEqualTo(TaskType.ORDER_CANCEL);
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should pass validation with valid referenceId")
        void shouldPassValidation() {
            var task = buildTask("ORD-123");
            handler.validate(task);
        }

        @Test
        @DisplayName("Should reject null referenceId")
        void shouldRejectNullReferenceId() {
            var task = buildTask(null);
            assertThatThrownBy(() -> handler.validate(task))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject blank referenceId")
        void shouldRejectBlankReferenceId() {
            var task = buildTask("  ");
            assertThatThrownBy(() -> handler.validate(task))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Execution Tests")
    class ExecutionTests {

        @Test
        @DisplayName("Should return success when order cancelled")
        void shouldReturnSuccessWhenOrderCancelled() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any())).thenReturn(
                    OrderCancelResponse.builder()
                            .orderId("ORD-123").status("CANCELLED").message("OK")
                            .cancelledAt(Instant.now().toString())
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResponseData()).containsEntry("orderId", "ORD-123");
            assertThat(result.getResponseData()).containsEntry("status", "CANCELLED");
        }

        @Test
        @DisplayName("Should return failure when unexpected response status")
        void shouldReturnFailureWhenUnexpectedStatus() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any())).thenReturn(
                    OrderCancelResponse.builder()
                            .orderId("ORD-123").status("PENDING").message("Still pending")
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("UNEXPECTED_STATUS");
        }

        @Test
        @DisplayName("Should return permanent failure when order not found (404)")
        void shouldReturnPermanentFailureWhenOrderNotFound() {
            var task = buildTask("ORD-999");
            when(orderServiceClient.cancelOrder(any()))
                    .thenThrow(new ExternalServiceException("Order Service", 404, "Not found"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("ORDER_NOT_FOUND");
        }

        @Test
        @DisplayName("Should return permanent failure on conflict (409)")
        void shouldReturnPermanentFailureOnConflict() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any()))
                    .thenThrow(new ExternalServiceException("Order Service", 409, "Already cancelled"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("ORDER_STATE_CONFLICT");
        }

        @Test
        @DisplayName("Should return permanent failure on bad request (400)")
        void shouldReturnPermanentFailureOnBadRequest() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any()))
                    .thenThrow(new ExternalServiceException("Order Service", 400, "Invalid request"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("Should return retryable failure on server error (500)")
        void shouldReturnRetryableFailureOnServerError() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any()))
                    .thenThrow(new ExternalServiceException("Order Service", 500, "Internal error"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isTrue();
            assertThat(result.getHttpStatusCode()).isEqualTo(500);
        }

        @Test
        @DisplayName("Should return retryable failure on generic exception")
        void shouldReturnRetryableFailureOnGenericException() {
            var task = buildTask("ORD-123");
            when(orderServiceClient.cancelOrder(any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("Should use default payload values when missing")
        void shouldUseDefaultPayloadValuesWhenMissing() {
            var task = buildTask("ORD-123");
            task.setPayload(null);

            when(orderServiceClient.cancelOrder(any())).thenReturn(
                    OrderCancelResponse.builder()
                            .orderId("ORD-123").status("CANCELLED").message("OK")
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Retry Delay Tests")
    class RetryDelayTests {

        @Test
        @DisplayName("Should calculate exponential backoff with jitter for first 3 retries")
        void shouldCalculateExponentialBackoffWithJitter() {
            var task = buildTask("ORD-123");

            // retry 0: base 1h = 3_600_000ms, with 10-25% jitter
            task.setRetryCount(0);
            long delay0 = handler.calculateNextRetryDelayMs(task, 24);
            assertThat(delay0).isBetween(3_960_000L, 4_500_001L); // 1h + 10-25%

            // retry 1: base 2h = 7_200_000ms
            task.setRetryCount(1);
            long delay1 = handler.calculateNextRetryDelayMs(task, 24);
            assertThat(delay1).isBetween(7_920_000L, 9_000_001L); // 2h + 10-25%

            // retry 2: base 4h = 14_400_000ms
            task.setRetryCount(2);
            long delay2 = handler.calculateNextRetryDelayMs(task, 24);
            assertThat(delay2).isBetween(15_840_000L, 18_000_001L); // 4h + 10-25%
        }

        @Test
        @DisplayName("Should use default delay after 3 retries")
        void shouldUseDefaultDelayAfterThreeRetries() {
            var task = buildTask("ORD-123");
            task.setRetryCount(3);

            long delay = handler.calculateNextRetryDelayMs(task, 24);
            long baseDailyMs = 24L * 60L * 60L * 1000L;
            assertThat(delay).isBetween(baseDailyMs + baseDailyMs / 10, baseDailyMs + baseDailyMs / 4 + 1);
        }

        @Test
        @DisplayName("Should use custom retry delay from metadata")
        void shouldUseCustomRetryDelayFromMetadata() {
            var task = buildTask("ORD-123");
            task.setMetadata(new HashMap<>(Map.of("retryDelayHours", 6)));

            long delay = handler.calculateNextRetryDelayMs(task, 24);
            long baseMs = 6L * 60L * 60L * 1000L;
            assertThat(delay).isBetween(baseMs + baseMs / 10, baseMs + baseMs / 4 + 1);
        }
    }

    private ScheduledTask buildTask(String referenceId) {
        return ScheduledTask.builder()
                .id(UUID.randomUUID())
                .taskType(TaskType.ORDER_CANCEL)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId(referenceId)
                .payload(new HashMap<>(Map.of("reason", "Customer request", "cancelledBy", "system")))
                .metadata(new HashMap<>())
                .retryCount(0)
                .scheduledTime(Instant.now())
                .build();
    }
}
