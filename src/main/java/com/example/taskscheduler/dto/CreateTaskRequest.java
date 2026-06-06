package com.example.taskscheduler.dto;

import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.validation.JsonSizeWithin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Request DTO for creating a new task
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTaskRequest {

    @NotNull(message = "Task type is required")
    private TaskType taskType;

    @NotBlank(message = "Reference ID is required")
    private String referenceId;

    private String secondaryReferenceId;

    private String description;

    private TaskPriority priority;

    /**
     * Task-specific data to be used by the handler
     */
    @JsonSizeWithin(maxBytes = 65_536, message = "payload exceeds maximum size (64 KiB)")
    private Map<String, Object> payload;

    /**
     * Control metadata for task execution
     */
    @JsonSizeWithin(maxBytes = 65_536, message = "metadata exceeds maximum size (64 KiB)")
    private Map<String, Object> metadata;

    /**
     * When to execute the task (default: immediately)
     */
    private Instant scheduledTime;

    /**
     * Task expiration time (optional)
     */
    private Instant expiresAt;

    /**
     * Override default max retries
     */
    private Integer maxRetries;

    /**
     * Override default retry delay (hours)
     */
    private Integer retryDelayHours;

    /**
     * Cron expression for recurring tasks
     */
    private String cronExpression;

    /**
     * Who created this task
     */
    private String createdBy;

    /**
     * Prevent duplicate tasks for same reference
     */
    @Builder.Default
    private boolean preventDuplicates = true;
}
