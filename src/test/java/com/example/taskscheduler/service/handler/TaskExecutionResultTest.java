package com.example.taskscheduler.service.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TaskExecutionResult Tests")
class TaskExecutionResultTest {

    @Test
    @DisplayName("Should create success result")
    void shouldCreateSuccessResult() {
        var result = TaskExecutionResult.success();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create success result with data")
    void shouldCreateSuccessResultWithData() {
        var data = Map.<String, Object>of("key", "value");
        var result = TaskExecutionResult.success(data);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponseData()).containsEntry("key", "value");
    }

    @Test
    @DisplayName("Should create failure result")
    void shouldCreateFailureResult() {
        var result = TaskExecutionResult.failure("Something went wrong");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Something went wrong");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create permanent failure")
    void shouldCreatePermanentFailure() {
        var result = TaskExecutionResult.permanentFailure("Not found", "NOT_FOUND");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorType()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Should create HTTP failure - 5xx is retryable and bucketed as HTTP_5XX")
    void shouldCreateRetryableHttpFailure() {
        var result = TaskExecutionResult.httpFailure(503, "Service Unavailable");
        assertThat(result.isRetryable()).isTrue();
        // Exact status preserved on httpStatusCode; errorType is the low-cardinality
        // bucket used as the task_scheduler_failures metric tag.
        assertThat(result.getHttpStatusCode()).isEqualTo(503);
        assertThat(result.getErrorType()).isEqualTo("HTTP_5XX");
    }

    @Test
    @DisplayName("Should create HTTP failure - 4xx is not retryable and bucketed as HTTP_4XX")
    void shouldCreateNonRetryableHttpFailure() {
        var result = TaskExecutionResult.httpFailure(400, "Bad Request");
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getHttpStatusCode()).isEqualTo(400);
        assertThat(result.getErrorType()).isEqualTo("HTTP_4XX");
    }

    @Test
    @DisplayName("Should create HTTP failure - 429 is retryable")
    void shouldCreate429AsRetryable() {
        var result = TaskExecutionResult.httpFailure(429, "Too Many Requests");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create HTTP failure - 408 is retryable")
    void shouldCreate408AsRetryable() {
        var result = TaskExecutionResult.httpFailure(408, "Request Timeout");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("Should create failure from exception with truncated stack trace")
    void shouldCreateFailureFromException() {
        var exception = new RuntimeException("Test error");
        var result = TaskExecutionResult.failure(exception);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Test error");
        assertThat(result.getErrorType()).isEqualTo("RuntimeException");
        assertThat(result.getStackTrace()).contains("RuntimeException");
    }

    @Test
    @DisplayName("Should support fluent API for adding response data")
    void shouldSupportFluentResponseData() {
        var result = TaskExecutionResult.success()
                .withResponseData("key1", "value1")
                .withResponseData("key2", 42);
        assertThat(result.getResponseData())
                .containsEntry("key1", "value1")
                .containsEntry("key2", 42);
    }

    @Test
    @DisplayName("Should support custom retry delay")
    void shouldSupportCustomRetryDelay() {
        var result = TaskExecutionResult.failure("error")
                .withCustomRetryDelay(5000L);
        assertThat(result.getCustomRetryDelayMs()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("Should support marking as non-retryable")
    void shouldSupportNonRetryable() {
        var result = TaskExecutionResult.failure("error").nonRetryable();
        assertThat(result.isRetryable()).isFalse();
    }
}
