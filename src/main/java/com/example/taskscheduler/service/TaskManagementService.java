package com.example.taskscheduler.service;

import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.dto.*;
import com.example.taskscheduler.exception.DuplicateTaskException;
import com.example.taskscheduler.exception.InvalidTaskStateException;
import com.example.taskscheduler.exception.TaskNotFoundException;
import com.example.taskscheduler.mapper.TaskMapper;
import com.example.taskscheduler.service.executor.TaskPollingService;
import com.example.taskscheduler.service.handler.TaskHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing task lifecycle operations.
 * <p>
 * Provides:
 * - Task creation with duplicate detection
 * - Task querying and searching
 * - Status management (cancel, pause, resume, retry)
 * - Bulk operations
 * - Statistics and reporting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskManagementService {

    private final ScheduledTaskRepository taskRepository;
    private final TaskExecutionLogRepository executionLogRepository;
    private final TaskPollingService taskPollingService;
    private final TaskMapper taskMapper;
    private final TaskHandlerRegistry handlerRegistry;

    // === Task Creation ===

    /**
     * Create a new scheduled task
     */
    @Transactional
    public TaskResponse createTask(CreateTaskRequest request) {
        log.info("Creating task of type {} for reference {}", request.getTaskType(), request.getReferenceId());

        // Per-handler validation runs before persistence so malformed payloads
        // surface as HTTP 400 without writing a row, acquiring a lock, or
        // burning a retry counter.
        var handler = handlerRegistry.getHandlerOrThrow(request.getTaskType());
        handler.validateForCreate(request);

        // Check for duplicate active task
        if (request.isPreventDuplicates()) {
            var exists = taskRepository.existsActiveTaskForReference(request.getReferenceId(), request.getTaskType());
            if (exists) {
                log.warn("Active task already exists for reference {} with type {}", request.getReferenceId(), request.getTaskType());
                throw new DuplicateTaskException(request.getReferenceId(), request.getTaskType().name());
            }
        }

        // Build task entity
        var task = ScheduledTask.builder()
                .taskType(request.getTaskType())
                .status(TaskStatus.PENDING)
                .priority(request.getPriority() != null ? request.getPriority() : TaskPriority.NORMAL)
                .referenceId(request.getReferenceId())
                .secondaryReferenceId(request.getSecondaryReferenceId())
                .description(request.getDescription())
                .payload(request.getPayload() != null ? request.getPayload() : new HashMap<>())
                .metadata(request.getMetadata() != null ? request.getMetadata() : new HashMap<>())
                .scheduledTime(request.getScheduledTime() != null ? request.getScheduledTime() : Instant.now())
                .expiresAt(request.getExpiresAt())
                .maxRetries(request.getMaxRetries())
                .retryDelayHours(request.getRetryDelayHours())
                .cronExpression(request.getCronExpression())
                .createdBy(request.getCreatedBy())
                .build();

        task = taskRepository.save(task);
        log.info("Created task {} for reference {}", task.getId(), request.getReferenceId());

        return taskMapper.toResponse(task);
    }

    /**
     * Create multiple tasks in a batch.
     * Returns both successfully created tasks and per-item errors.
     */
    @Transactional
    public BatchCreateResult createTasks(List<CreateTaskRequest> requests) {
        var created = new ArrayList<TaskResponse>();
        var errors = new ArrayList<BatchCreateResult.BatchItemError>();

        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get(i);
            try {
                created.add(createTask(request));
            } catch (Exception e) {
                log.error("Failed to create task for reference {}: {}",
                        request.getReferenceId(), e.getMessage());
                errors.add(BatchCreateResult.BatchItemError.builder()
                        .index(i)
                        .referenceId(request.getReferenceId())
                        .error(e.getMessage())
                        .build());
            }
        }

        return BatchCreateResult.builder().created(created).errors(errors).build();
    }

    // === Task Retrieval ===

    /**
     * Get task by ID
     */
    @Transactional(readOnly = true)
    public Optional<TaskResponse> getTask(UUID taskId) {
        return taskRepository.findById(taskId).map(taskMapper::toResponse);
    }

    /**
     * Get task with execution history
     */
    @Transactional(readOnly = true)
    public Optional<TaskResponse> getTaskWithHistory(UUID taskId) {
        return taskRepository.findById(taskId)
                .map(task -> {
                    var response = taskMapper.toResponse(task);
                    var logs = executionLogRepository.findByTaskIdOrderByAttemptNumberDesc(taskId);
                    response.setExecutionHistory(taskMapper.toLogResponses(logs));
                    return response;
                });
    }

    /**
     * Get tasks by reference ID
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksByReference(String referenceId) {
        return taskMapper.toResponseList(taskRepository.findByReferenceIdOrderByCreatedAtDesc(referenceId));
    }

    /**
     * Search tasks with filters
     */
    @Transactional(readOnly = true)
    public Page<TaskResponse> searchTasks(TaskSearchCriteria criteria, Pageable pageable) {
        Page<ScheduledTask> tasks;

        var hasRef = criteria.getReferenceId() != null && !criteria.getReferenceId().isBlank();
        var hasType = criteria.getTaskType() != null;
        var hasStatus = criteria.getStatus() != null;

        if (hasRef && hasType && hasStatus) {
            tasks = taskRepository.findByReferenceIdAndTaskTypeAndStatus(criteria.getReferenceId(), criteria.getTaskType(), criteria.getStatus(), pageable);
        } else if (hasRef && hasType) {
            tasks = taskRepository.findByReferenceIdAndTaskType(criteria.getReferenceId(), criteria.getTaskType(), pageable);
        } else if (hasRef && hasStatus) {
            tasks = taskRepository.findByReferenceIdAndStatus(criteria.getReferenceId(), criteria.getStatus(), pageable);
        } else if (hasRef) {
            tasks = taskRepository.findByReferenceId(criteria.getReferenceId(), pageable);
        } else if (hasType && hasStatus) {
            tasks = taskRepository.findByTaskTypeAndStatus(criteria.getTaskType(), criteria.getStatus(), pageable);
        } else if (hasType) {
            tasks = taskRepository.findByTaskType(criteria.getTaskType(), pageable);
        } else if (hasStatus) {
            tasks = taskRepository.findByStatus(criteria.getStatus(), pageable);
        } else {
            tasks = taskRepository.findAll(pageable);
        }

        return tasks.map(taskMapper::toResponse);
    }

    /**
     * Get tasks scheduled within a time range
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksInTimeRange(Instant start, Instant end, List<TaskStatus> statuses) {
        return taskMapper.toResponseList(taskRepository.findTasksInTimeRange(start, end, statuses));
    }

    // === Task Status Management ===

    /**
     * Cancel a task
     */
    @Transactional
    public TaskResponse cancelTask(UUID taskId, String reason) {
        var task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        if (task.getStatus().isTerminal()) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name(), TaskStatus.CANCELLED.name());
        }

        if (task.isLocked()) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name() + " (locked)", TaskStatus.CANCELLED.name());
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        task.setLastError("Cancelled: " + (reason != null ? reason : "Manual cancellation"));

        task = taskRepository.save(task);
        log.info("Cancelled task {}: {}", taskId, reason);

        return taskMapper.toResponse(task);
    }

    /**
     * Pause a task
     */
    @Transactional
    public TaskResponse pauseTask(UUID taskId) {
        var task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        if (task.getStatus().isTerminal()) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name(), TaskStatus.PAUSED.name());
        }

        if (task.isLocked()) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name() + " (locked)", TaskStatus.PAUSED.name());
        }

        task.setStatus(TaskStatus.PAUSED);
        task = taskRepository.save(task);
        log.info("Paused task {}", taskId);

        return taskMapper.toResponse(task);
    }

    /**
     * Resume a paused task
     */
    @Transactional
    public TaskResponse resumeTask(UUID taskId) {
        var task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        if (task.getStatus() != TaskStatus.PAUSED) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name(), TaskStatus.PENDING.name());
        }

        task.setStatus(TaskStatus.PENDING);
        task.setScheduledTime(Instant.now()); // Schedule for immediate execution
        task = taskRepository.save(task);
        log.info("Resumed task {}", taskId);

        return taskMapper.toResponse(task);
    }

    /**
     * Manually retry a failed task
     */
    @Transactional
    public TaskResponse retryTask(UUID taskId, Instant scheduledTime) {
        var task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        if (!task.getStatus().isFailure() && task.getStatus() != TaskStatus.PAUSED) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name(), TaskStatus.RETRY_PENDING.name());
        }

        task.setStatus(TaskStatus.RETRY_PENDING);
        task.setScheduledTime(scheduledTime != null ? scheduledTime : Instant.now());
        task.setLockedBy(null);
        task.setLockedUntil(null);

        task = taskRepository.save(task);
        log.info("Scheduled retry for task {} at {}", taskId, task.getScheduledTime());

        return taskMapper.toResponse(task);
    }

    /**
     * Retry task immediately
     */
    @Transactional
    public CompletableFuture<TaskResponse> retryTaskNow(UUID taskId) {
        var task = taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        if (!task.getStatus().isFailure() && task.getStatus() != TaskStatus.PAUSED) {
            throw new InvalidTaskStateException(taskId.toString(), task.getStatus().name(), TaskStatus.PENDING.name());
        }

        // Reset task for immediate retry
        task.setStatus(TaskStatus.PENDING);
        task.setScheduledTime(Instant.now());
        task.setLockedBy(null);
        task.setLockedUntil(null);
        taskRepository.save(task);

        // Trigger async execution
        return taskPollingService.processTaskAsync(taskId)
                .thenApply(success -> {
                    ScheduledTask updated = taskRepository.findById(taskId).orElse(task);
                    return taskMapper.toResponse(updated);
                });
    }

    // === Bulk Operations ===

    /**
     * Cancel multiple tasks
     */
    @Transactional
    public int cancelTasks(List<UUID> taskIds, String reason) {
        var cancelled = 0;
        for (var taskId : taskIds) {
            try {
                cancelTask(taskId, reason);
                cancelled++;
            } catch (Exception e) {
                log.warn("Failed to cancel task {}: {}", taskId, e.getMessage());
            }
        }
        return cancelled;
    }

    /**
     * Bulk update task status
     */
    @Transactional
    public int bulkUpdateStatus(List<UUID> taskIds, TaskStatus newStatus) {
        return taskRepository.bulkUpdateStatus(taskIds, newStatus, Instant.now());
    }

    // === Statistics ===

    /**
     * Get task statistics
     */
    @Transactional(readOnly = true)
    public TaskStatistics getStatistics() {
        var stats = new TaskStatistics();

        // Status distribution
        var statusCounts = new HashMap<String, Long>();
        for (var row : taskRepository.getTaskStatsByStatus()) {
            var status = (TaskStatus) row[0];
            var count = (Long) row[1];
            statusCounts.put(status.name(), count);
        }
        stats.setStatusDistribution(statusCounts);

        // Type distribution
        var typeCounts = new HashMap<String, Map<String, Long>>();
        for (var row : taskRepository.getTaskStatsByTypeAndStatus()) {
            var type = (TaskType) row[0];
            var status = (TaskStatus) row[1];
            var count = (Long) row[2];
            typeCounts.computeIfAbsent(type.name(), k -> new HashMap<>()).put(status.name(), count);
        }
        stats.setTypeStatusDistribution(typeCounts);

        // Summary counts
        stats.setPendingCount(taskRepository.countByStatus(TaskStatus.PENDING) +
                taskRepository.countByStatus(TaskStatus.RETRY_PENDING) +
                taskRepository.countByStatus(TaskStatus.SCHEDULED));
        stats.setProcessingCount(taskRepository.countByStatus(TaskStatus.PROCESSING));
        stats.setFailedCount(taskRepository.countByStatus(TaskStatus.FAILED) +
                taskRepository.countByStatus(TaskStatus.MAX_RETRIES_EXCEEDED));
        stats.setCompletedCount(taskRepository.countByStatus(TaskStatus.COMPLETED));

        return stats;
    }

    // === Cleanup ===

    /**
     * Delete old completed tasks
     */
    @Transactional
    public int cleanupOldTasks(int retentionDays) {
        var cutoff = Instant.now().minusSeconds(retentionDays * 24L * 60L * 60L);

        // Delete old execution logs first
        var logsDeleted = executionLogRepository.deleteOldLogs(cutoff);

        // Delete old tasks
        var tasksDeleted = taskRepository.deleteOldCompletedTasks(cutoff);

        log.info("Cleaned up {} old tasks and {} execution logs older than {} days", tasksDeleted, logsDeleted, retentionDays);

        return tasksDeleted;
    }
}
