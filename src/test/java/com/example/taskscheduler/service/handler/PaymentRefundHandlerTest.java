package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.client.ClientModels.PaymentRefundResponse;
import com.example.taskscheduler.client.PaymentServiceClient;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRefundHandler Tests")
class PaymentRefundHandlerTest {

    @Mock
    private PaymentServiceClient paymentServiceClient;

    private PaymentRefundHandler handler;

    @BeforeEach
    void setUp() {
        // Real RetryDelayCalculator with default jitter band (10-25%) so the
        // existing delay-range assertions in this test class continue to hold.
        handler = new PaymentRefundHandler(paymentServiceClient,
                new RetryDelayCalculator(new TaskSchedulerProperties()));
    }

    @Test
    @DisplayName("Should return PAYMENT_REFUND task type")
    void shouldReturnCorrectTaskType() {
        assertThat(handler.getTaskType()).isEqualTo(TaskType.PAYMENT_REFUND);
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should pass validation with valid referenceId")
        void shouldPassValidation() {
            var task = buildTask("PAY-123");
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
        @DisplayName("Should return success when refund processed")
        void shouldReturnSuccessWhenRefundProcessed() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any())).thenReturn(
                    PaymentRefundResponse.builder()
                            .refundId("REF-789").paymentId("PAY-123").status("REFUNDED")
                            .amount(BigDecimal.valueOf(99.99)).message("OK")
                            .processedAt(Instant.now().toString())
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResponseData()).containsEntry("paymentId", "PAY-123");
            assertThat(result.getResponseData()).containsEntry("status", "REFUNDED");
        }

        @Test
        @DisplayName("Should accept COMPLETED status as success")
        void shouldAcceptCompletedStatus() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any())).thenReturn(
                    PaymentRefundResponse.builder()
                            .refundId("REF-789").paymentId("PAY-123").status("COMPLETED")
                            .amount(BigDecimal.valueOf(99.99)).message("OK")
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Should return failure when unexpected response status")
        void shouldReturnFailureWhenUnexpectedStatus() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any())).thenReturn(
                    PaymentRefundResponse.builder()
                            .paymentId("PAY-123").status("PENDING").message("Still processing")
                            .build());

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("UNEXPECTED_STATUS");
        }

        @Test
        @DisplayName("Should return permanent failure when payment not found (404)")
        void shouldReturnPermanentFailureWhenPaymentNotFound() {
            var task = buildTask("PAY-999");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new ExternalServiceException("Payment Service", 404, "Not found"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("PAYMENT_NOT_FOUND");
        }

        @Test
        @DisplayName("Should return permanent failure on conflict (409)")
        void shouldReturnPermanentFailureOnConflict() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new ExternalServiceException("Payment Service", 409, "Already refunded"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("PAYMENT_STATE_CONFLICT");
        }

        @Test
        @DisplayName("Should return permanent failure on bad request (400)")
        void shouldReturnPermanentFailureOnBadRequest() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new ExternalServiceException("Payment Service", 400, "Invalid request"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("Should return permanent failure on business rule violation (422)")
        void shouldReturnPermanentFailureOnBusinessRuleViolation() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new ExternalServiceException("Payment Service", 422, "Insufficient funds"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isFalse();
            assertThat(result.getErrorType()).isEqualTo("BUSINESS_RULE_VIOLATION");
        }

        @Test
        @DisplayName("Should return retryable failure on server error (500)")
        void shouldReturnRetryableFailureOnServerError() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new ExternalServiceException("Payment Service", 500, "Internal error"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isTrue();
            assertThat(result.getHttpStatusCode()).isEqualTo(500);
        }

        @Test
        @DisplayName("Should return retryable failure on generic exception")
        void shouldReturnRetryableFailureOnGenericException() {
            var task = buildTask("PAY-123");
            when(paymentServiceClient.refundPayment(any()))
                    .thenThrow(new RuntimeException("Connection refused"));

            var result = handler.execute(task);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isRetryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("Retry Delay Tests")
    class RetryDelayTests {

        @Test
        @DisplayName("Should calculate conservative retry delays with jitter")
        void shouldCalculateRetryDelayWithJitter() {
            var task = buildTask("PAY-123");

            // retry 0: base 2h = 7,200,000ms
            task.setRetryCount(0);
            long delay0 = handler.calculateNextRetryDelayMs(task, 24);
            long base2h = 2L * 60L * 60L * 1000L;
            assertThat(delay0).isBetween(base2h + base2h / 10, base2h + base2h / 4 + 1);

            // retry 1: (3 + 1*3) = 6h = 21,600,000ms
            task.setRetryCount(1);
            long delay1 = handler.calculateNextRetryDelayMs(task, 24);
            long base6h = 6L * 60L * 60L * 1000L;
            assertThat(delay1).isBetween(base6h + base6h / 10, base6h + base6h / 4 + 1);

            // retry 2: (3 + 2*3) = 9h = 32,400,000ms
            task.setRetryCount(2);
            long delay2 = handler.calculateNextRetryDelayMs(task, 24);
            long base9h = 9L * 60L * 60L * 1000L;
            assertThat(delay2).isBetween(base9h + base9h / 10, base9h + base9h / 4 + 1);
        }

        @Test
        @DisplayName("Should use default delay after 3 retries")
        void shouldUseDefaultDelayAfterThreeRetries() {
            var task = buildTask("PAY-123");
            task.setRetryCount(3);

            long delay = handler.calculateNextRetryDelayMs(task, 24);
            long baseDailyMs = 24L * 60L * 60L * 1000L;
            assertThat(delay).isBetween(baseDailyMs + baseDailyMs / 10, baseDailyMs + baseDailyMs / 4 + 1);
        }

        @Test
        @DisplayName("Should use custom retry delay from metadata")
        void shouldUseCustomRetryDelayFromMetadata() {
            var task = buildTask("PAY-123");
            task.setMetadata(new HashMap<>(Map.of("retryDelayHours", 4)));

            long delay = handler.calculateNextRetryDelayMs(task, 24);
            long baseMs = 4L * 60L * 60L * 1000L;
            assertThat(delay).isBetween(baseMs + baseMs / 10, baseMs + baseMs / 4 + 1);
        }
    }

    private ScheduledTask buildTask(String referenceId) {
        return ScheduledTask.builder()
                .id(UUID.randomUUID())
                .taskType(TaskType.PAYMENT_REFUND)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId(referenceId)
                .secondaryReferenceId("TXN-456")
                .payload(new HashMap<>(Map.of("amount", 99.99, "currency", "USD", "reason", "Customer refund")))
                .metadata(new HashMap<>())
                .retryCount(0)
                .scheduledTime(Instant.now())
                .build();
    }
}
