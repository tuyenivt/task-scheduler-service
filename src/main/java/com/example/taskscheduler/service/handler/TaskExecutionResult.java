package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.domain.enums.TaskType;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the result of a task execution.
 * <p>
 * Contains all information needed to update the task record
 * and create execution logs.
 */
@Data
@Builder
public class TaskExecutionResult {

    /**
     * Whether the execution was successful
     */
    private boolean success;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * Error type/classification for analysis
     */
    private String errorType;

    /**
     * Stack trace if available
     */
    private String stackTrace;

    /**
     * HTTP status code if applicable
     */
    private Integer httpStatusCode;

    /**
     * Response data from external service
     */
    @Builder.Default
    private Map<String, Object> responseData = new HashMap<>();

    /**
     * Whether this failure should be retried
     * Some failures (like validation errors) should not be retried
     */
    @Builder.Default
    private boolean retryable = true;

    /**
     * Custom next retry delay in milliseconds (overrides default)
     * Null means use default calculation
     */
    private Long customRetryDelayMs;

    /**
     * Additional notes or context
     */
    private String notes;

    /**
     * Create a success result
     */
    public static TaskExecutionResult success() {
        return TaskExecutionResult.builder().success(true).build();
    }

    /**
     * Create a success result with response data
     */
    public static TaskExecutionResult success(Map<String, Object> responseData) {
        return TaskExecutionResult.builder()
                .success(true)
                .responseData(responseData != null ? responseData : new HashMap<>())
                .build();
    }

    /**
     * Create a failure result
     */
    public static TaskExecutionResult failure(String errorMessage) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .retryable(true)
                .build();
    }

    /**
     * Create a failure result with type
     */
    public static TaskExecutionResult failure(String errorMessage, String errorType) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .errorType(errorType)
                .retryable(true)
                .build();
    }

    /**
     * Format a "downstream returned an unexpected response status" message in a
     * consistent shape across handlers. Centralizing the template keeps
     * log-grepping and alerting rules simple - every handler emits the same
     * pattern and only the {TaskType} segment differs.
     */
    public static String unexpectedStatusMessage(TaskType taskType, String status, String detail) {
        return String.format("%s unexpected response status: %s - %s",
                taskType.getDisplayName(), status, detail);
    }

    /**
     * Convenience: build a retryable failure for the "unexpected status"
     * case with the standardized message and {@code UNEXPECTED_STATUS}
     * error type so alerting can route on a stable label.
     */
    public static TaskExecutionResult unexpectedStatus(TaskType taskType, String status, String detail) {
        return failure(unexpectedStatusMessage(taskType, status, detail), "UNEXPECTED_STATUS");
    }

    /**
     * Create a failure result from exception
     */
    public static TaskExecutionResult failure(Exception e) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .errorType(e.getClass().getSimpleName())
                .stackTrace(truncateStackTrace(e))
                .retryable(true)
                .build();
    }

    /**
     * Create a non-retryable failure (permanent failure)
     */
    public static TaskExecutionResult permanentFailure(String errorMessage, String errorType) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .errorType(errorType)
                .retryable(false)
                .build();
    }

    /**
     * Create a failure result with HTTP status.
     * <p>
     * The exact status code is preserved on {@code httpStatusCode} for the
     * execution-log row, but {@code errorType} is bucketed to {@code HTTP_4XX}
     * / {@code HTTP_5XX} so the {@code task_scheduler_failures} metric tag
     * stays low cardinality - alerting routes on symptom class, not on the
     * exact status of every individual failure.
     */
    public static TaskExecutionResult httpFailure(int statusCode, String errorMessage) {
        boolean retryable = statusCode >= 500 || statusCode == 408 || statusCode == 429;
        String bucket = statusCode >= 500 ? "HTTP_5XX" : "HTTP_4XX";
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .errorType(bucket)
                .httpStatusCode(statusCode)
                .retryable(retryable)
                .build();
    }

    /**
     * Truncate stack trace to prevent database overflow
     */
    private static String truncateStackTrace(Exception e) {
        if (e == null) return null;

        var sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");

        var trace = e.getStackTrace();
        var maxLines = Math.min(trace.length, 20);
        for (var i = 0; i < maxLines; i++) {
            sb.append("\tat ").append(trace[i]).append("\n");
        }
        if (trace.length > maxLines) {
            sb.append("\t... ").append(trace.length - maxLines).append(" more\n");
        }

        // Limit total length
        var result = sb.toString();
        if (result.length() > 4000) {
            result = result.substring(0, 4000) + "...";
        }
        return result;
    }

    /**
     * Add response data entry
     */
    public TaskExecutionResult withResponseData(String key, Object value) {
        if (this.responseData == null) {
            this.responseData = new HashMap<>();
        }
        this.responseData.put(key, value);
        return this;
    }

    /**
     * Set custom retry delay
     */
    public TaskExecutionResult withCustomRetryDelay(long delayMs) {
        this.customRetryDelayMs = delayMs;
        return this;
    }

    /**
     * Mark as non-retryable
     */
    public TaskExecutionResult nonRetryable() {
        this.retryable = false;
        return this;
    }
}
