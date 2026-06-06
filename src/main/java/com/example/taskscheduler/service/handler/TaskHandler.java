package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.dto.CreateTaskRequest;

/**
 * Interface for task handlers.
 * <p>
 * Each task type should have a corresponding handler implementation
 * that knows how to execute that specific type of task.
 * <p>
 * Handlers should:
 * - Be stateless
 * - Handle their own error translation
 * - Return clear success/failure results
 * - Not manage transactions (handled by executor)
 */
public interface TaskHandler {

    /**
     * Get the task type this handler supports
     */
    TaskType getTaskType();

    /**
     * Execute the task
     *
     * @param task The task to execute
     * @return Result of the execution
     */
    TaskExecutionResult execute(ScheduledTask task);

    /**
     * Check if this handler supports the given task type
     */
    default boolean supports(TaskType taskType) {
        return getTaskType() == taskType;
    }

    /**
     * Validate a creation request before persistence.
     * <p>
     * Runs synchronously inside the create transaction so handler-specific
     * invariants (required payload keys, type coercions, business rules)
     * can reject the request with HTTP 400 before a row is written. Catches
     * malformed input that would otherwise only surface mid-execution -
     * after lock acquisition, MDC setup, and an execution-log row have
     * already been written.
     *
     * @param request the creation request
     * @throws IllegalArgumentException if validation fails (mapped to 400)
     */
    default void validateForCreate(CreateTaskRequest request) {
        if (request.getReferenceId() == null || request.getReferenceId().isBlank()) {
            throw new IllegalArgumentException("Task reference ID is required");
        }
    }

    /**
     * Validate task before execution (optional override)
     *
     * @param task The task to validate
     * @throws IllegalArgumentException if validation fails
     */
    default void validate(ScheduledTask task) {
        if (task.getReferenceId() == null || task.getReferenceId().isBlank()) {
            throw new IllegalArgumentException("Task reference ID is required");
        }
    }

    /**
     * Calculate next retry time for this task (optional override)
     * Default implementation schedules retry after configured delay
     *
     * @param task              The failed task
     * @param defaultDelayHours Default delay in hours
     * @return Next retry time in milliseconds from now
     */
    default long calculateNextRetryDelayMs(ScheduledTask task, int defaultDelayHours) {
        var delayHours = task.getEffectiveRetryDelayHours(defaultDelayHours);
        return delayHours * 60L * 60L * 1000L;
    }
}
