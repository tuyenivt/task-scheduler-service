package com.example.taskscheduler.domain.entity;

import com.example.taskscheduler.domain.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Execution log entry for tracking task execution history.
 * Each execution attempt creates a new log entry.
 */
@Entity
@Table(name = "task_execution_logs", indexes = {
        @Index(name = "idx_exec_log_task_id", columnList = "task_id"),
        @Index(name = "idx_exec_log_executed_at", columnList = "started_at"),
        @Index(name = "idx_exec_log_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Reference to the parent {@code scheduled_tasks(id)}.
     * <p>
     * Enforced as a foreign key with {@code ON DELETE CASCADE} at the schema
     * level (see V3 migration) - deleting a task row automatically removes its
     * execution logs, so the retention job does not need to coordinate log
     * retention vs task retention windows. Modeled as a raw {@code UUID} (not
     * a {@code @ManyToOne}) to avoid the join overhead and lazy-load surprises;
     * execution log rows are append-only history and almost never joined to
     * their parent at runtime.
     */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /**
     * Which attempt number this was
     */
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    /**
     * Status at the end of this execution
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TaskStatus status;

    /**
     * Instance that executed this task
     */
    @Column(name = "executor_instance", length = 100)
    private String executorInstance;

    /**
     * When execution started
     */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
     * When execution completed
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Duration in milliseconds
     */
    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Whether this execution was successful
     */
    @Column(name = "success", nullable = false)
    private Boolean success;

    /**
     * Error message if failed
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Error stack trace (truncated)
     */
    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    private String errorStackTrace;

    /**
     * Error classification/type
     */
    @Column(name = "error_type", length = 200)
    private String errorType;

    /**
     * HTTP status code if applicable
     */
    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    /**
     * Request payload sent
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private Map<String, Object> requestPayload;

    /**
     * Response received
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", columnDefinition = "jsonb")
    private Map<String, Object> responsePayload;

    /**
     * Additional notes or context
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    /**
     * Calculate duration if not set
     */
    public void calculateDuration() {
        if (startedAt != null && completedAt != null) {
            this.durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
}
