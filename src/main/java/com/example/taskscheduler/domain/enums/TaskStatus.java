package com.example.taskscheduler.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Comprehensive task status enum covering the complete task lifecycle.
 * Follows typical task scheduler state machine patterns.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public enum TaskStatus {

    /**
     * Task is created and waiting to be picked up for execution.
     * Initial state for all new tasks.
     */
    PENDING("pending", "Pending", true),

    /**
     * Task is scheduled for future execution.
     * Used when a specific scheduled time is set.
     */
    SCHEDULED("scheduled", "Scheduled", true),

    /**
     * Task has been picked up by an executor and is currently running.
     * Only one instance should process a task at a time.
     */
    PROCESSING("processing", "Processing", false),

    /**
     * Task completed successfully.
     * Terminal state - no further processing needed.
     */
    COMPLETED("completed", "Completed", false),

    /**
     * Task execution failed but can be retried.
     * Will be picked up again based on retry configuration.
     */
    FAILED("failed", "Failed", true),

    /**
     * Task waiting for retry after a failure.
     * Has a scheduled next execution time.
     */
    RETRY_PENDING("retry-pending", "Retry Pending", true),

    /**
     * Task has exceeded maximum retry attempts.
     * Terminal state - requires manual intervention.
     */
    MAX_RETRIES_EXCEEDED("max-retries-exceeded", "Max Retries Exceeded", false),

    /**
     * Task was manually canceled by an operator.
     * Terminal state.
     */
    CANCELLED("cancelled", "Cancelled", false),

    /**
     * Task is paused and will not be executed until resumed.
     * Can be transitioned back to PENDING.
     */
    PAUSED("paused", "Paused", false),

    /**
     * Task has expired without being executed.
     * Used when tasks have a deadline that has passed.
     */
    EXPIRED("expired", "Expired", false),

    /**
     * Task is in a dead-letter state for investigation.
     * Usually moved here after persistent failures.
     */
    DEAD_LETTER("dead-letter", "Dead Letter", false);

    private final String code;
    private final String displayName;

    /**
     * Indicates if the task in this status is eligible to be picked up for execution
     */
    private final boolean executable;

    /**
     * Find TaskStatus by its code value.
     * <p>
     * Logs the offending code and the full set of valid codes at WARN before
     * raising, so operators investigating a row with a corrupt or out-of-date
     * status column can identify the bad value without re-running the failure
     * to capture the exception message.
     */
    public static TaskStatus fromCode(String code) {
        for (var status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        var validCodes = Arrays.stream(values())
                .map(TaskStatus::getCode)
                .collect(Collectors.joining(", "));
        log.warn("TaskStatus.fromCode: unknown code='{}' (valid codes: [{}])", code, validCodes);
        throw new IllegalArgumentException("Unknown task status code: " + code);
    }

    /**
     * Check if this status represents a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED ||
                this == CANCELLED ||
                this == EXPIRED ||
                this == MAX_RETRIES_EXCEEDED ||
                this == DEAD_LETTER;
    }

    /**
     * Check if this status indicates a failure condition
     */
    public boolean isFailure() {
        return this == FAILED ||
                this == MAX_RETRIES_EXCEEDED ||
                this == DEAD_LETTER;
    }
}
