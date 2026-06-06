package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.MetricsConfig;
import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.entity.TaskExecutionLog;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.service.alert.SlackAlertService;
import com.example.taskscheduler.service.handler.TaskExecutionResult;
import com.example.taskscheduler.service.handler.TaskHandlerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for executing individual tasks.
 * <p>
 * Handles:
 * - Task lock acquisition and release
 * - Handler invocation
 * - Result processing and status updates
 * - Retry scheduling
 * - Execution logging
 * - Metrics recording
 * - Alert triggering for max retries
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final ScheduledTaskRepository taskRepository;
    private final TaskExecutionLogRepository executionLogRepository;
    private final TaskHandlerRegistry handlerRegistry;
    private final SlackAlertService slackAlertService;
    private final MetricsConfig metricsConfig;
    private final TaskSchedulerProperties properties;

    /**
     * Self-reference used for proxy-mediated transactional calls so that
     * REQUIRES_NEW boundaries on emergency lock-release actually take effect
     * (direct {@code this} calls would bypass the Spring AOP proxy).
     */
    @Lazy
    private final TaskExecutorService self;

    @Value("${HOSTNAME:unknown}")
    private String hostname;

    private String instanceId;

    @PostConstruct
    void initInstanceId() {
        try {
            var host = InetAddress.getLocalHost().getHostName();
            instanceId = host + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            instanceId = hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        log.info("TaskExecutorService instance ID: {}", instanceId);
    }

    private String getInstanceId() {
        return instanceId;
    }

    /**
     * Execute a single task with full lifecycle management.
     * <p>
     * The caller must have already acquired the lock for this task via
     * {@link #acquireLockAndFetch(UUID)} — the returned entity is the
     * authoritative locked snapshot and is executed without further re-fetching.
     * <p>
     * If the inner transactional execution rolls back or throws (DB blip, pool
     * timeout, save() failure inside the failure-handling path), this method
     * issues an emergency lock release in a separate REQUIRES_NEW transaction so
     * the row does not sit locked until its TTL expires. The task is rescheduled
     * for immediate retry on a subsequent polling cycle.
     *
     * @param task The locked task to execute
     * @return true if execution was successful
     */
    public boolean executeTask(ScheduledTask task) {
        var taskId = task.getId().toString();

        MDC.put("taskId", taskId);
        MDC.put("taskType", task.getTaskType().name());
        MDC.put("referenceId", task.getReferenceId());

        try {
            return self.executeTaskInTx(task);
        } catch (RuntimeException e) {
            log.error("Execution transaction failed for task {}; performing emergency lock release: {}",
                    taskId, e.getMessage(), e);
            try {
                self.releaseLockAfterTxFailure(task.getId());
            } catch (RuntimeException releaseEx) {
                log.error("Emergency lock release failed for task {}; row will remain locked until TTL expires: {}",
                        taskId, releaseEx.getMessage(), releaseEx);
            }
            return false;
        } finally {
            MDC.remove("taskId");
            MDC.remove("taskType");
            MDC.remove("referenceId");
        }
    }

    /**
     * Transactional inner boundary for task execution. Invoked via the proxy
     * ({@code self}) so the @Transactional semantics actually apply when called
     * from the same bean.
     */
    @Transactional
    public boolean executeTaskInTx(ScheduledTask task) {
        return doExecuteTask(task);
    }

    /**
     * Release a lock held by this instance after the main execution transaction
     * has rolled back. Runs in its own transaction so it commits independently
     * of the failed outer work. No-op if the task is already in a terminal state
     * or no longer locked by this instance.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLockAfterTxFailure(UUID taskId) {
        var now = Instant.now();
        var nextRetry = now.plusSeconds(60);
        var rows = taskRepository.releaseLockForRetry(taskId, getInstanceId(), nextRetry, now);
        if (rows == 1) {
            log.warn("Emergency-released lock for task {}; scheduled for retry at {}", taskId, nextRetry);
        } else {
            log.debug("Emergency lock release for task {} was a no-op (already released or terminal)", taskId);
        }
    }

    private boolean doExecuteTask(ScheduledTask task) {
        var taskId = task.getId().toString();

        log.info("Starting execution of task {} (type: {}, reference: {})", taskId, task.getTaskType(), task.getReferenceId());

        var timerSample = metricsConfig.startTaskExecutionTimer();
        var startTime = Instant.now();
        var executionLog = createExecutionLog(task, startTime);

        try {
            // Validate task can be executed
            if (!canExecute(task)) {
                log.warn("Task {} cannot be executed in current state: {}",
                        taskId, task.getStatus());
                return false;
            }

            // Get handler for this task type
            var handler = handlerRegistry.getHandlerOrThrow(task.getTaskType());

            // Validate task data
            try {
                handler.validate(task);
            } catch (IllegalArgumentException e) {
                log.error("Task {} validation failed: {}", taskId, e.getMessage());
                handlePermanentFailure(task, executionLog, "VALIDATION_ERROR", e.getMessage());
                return false;
            }

            // Execute the task
            var result = handler.execute(task);

            // Process result
            var endTime = Instant.now();
            var durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

            if (result.isSuccess()) {
                handleSuccess(task, executionLog, result, endTime, durationMs);
                metricsConfig.recordTaskExecution(timerSample, task.getTaskType(), true);
                return true;
            } else {
                handleFailure(task, executionLog, result, endTime, durationMs);
                metricsConfig.recordTaskExecution(timerSample, task.getTaskType(), false);
                metricsConfig.recordTaskFailure(task.getTaskType(), result.getErrorType());
                return false;
            }
        } catch (Exception e) {
            log.error("Unexpected error executing task {}: {}", taskId, e.getMessage(), e);
            var endTime = Instant.now();
            var durationMs = endTime.toEpochMilli() - startTime.toEpochMilli();

            var result = TaskExecutionResult.failure(e);
            handleFailure(task, executionLog, result, endTime, durationMs);

            metricsConfig.recordTaskExecution(timerSample, task.getTaskType(), false);
            metricsConfig.recordTaskFailure(task.getTaskType(), e.getClass().getSimpleName());

            return false;
        }
    }

    /**
     * Atomically acquire the per-task lock and return the post-lock snapshot.
     * <p>
     * The atomic UPDATE is the single source of truth for who owns the task; the
     * returned entity reflects the row state after the lock was won (re-read in
     * the same transaction), so the caller does not need — and must not perform —
     * a separate re-fetch. This eliminates the race where a stale version captured
     * during polling would be revalidated by a separate read between lock acquire
     * and execution.
     *
     * @return the locked task if the lock was won; empty otherwise
     */
    @Transactional
    public Optional<ScheduledTask> acquireLockAndFetch(UUID taskId) {
        var now = Instant.now();
        var lockUntil = now.plusSeconds(properties.getLockDurationMinutes() * 60L);

        var updated = taskRepository.acquireTaskLock(taskId, getInstanceId(), lockUntil, now);

        if (updated != 1) {
            log.debug("Failed to acquire lock for task {} (already locked by another instance)", taskId);
            return Optional.empty();
        }

        var locked = taskRepository.findById(taskId);
        if (locked.isEmpty()) {
            log.warn("Task {} was locked but disappeared before re-read", taskId);
            return Optional.empty();
        }

        log.debug("Acquired lock for task {} until {}", taskId, lockUntil);
        return locked;
    }

    private boolean canExecute(ScheduledTask task) {
        // Check if task is expired
        if (task.isExpired()) {
            log.info("Task {} has expired, marking as EXPIRED", task.getId());
            task.setStatus(TaskStatus.EXPIRED);
            taskRepository.save(task);
            return false;
        }

        // Check if status allows execution
        return task.getStatus().isExecutable() || task.getStatus() == TaskStatus.PROCESSING;
    }

    private void handleSuccess(ScheduledTask task, TaskExecutionLog executionLog, TaskExecutionResult result, Instant endTime, long durationMs) {
        log.info("Task {} completed successfully in {}ms", task.getId(), durationMs);

        var nextFire = computeNextCronFire(task, endTime);

        if (nextFire != null) {
            // Recurring task: reschedule for next fire. retryCount resets so each
            // fire has its own retry budget.
            task.setStatus(TaskStatus.SCHEDULED);
            task.setScheduledTime(nextFire);
            task.setRetryCount(0);
            task.setStartedAt(null);
            task.setCompletedAt(null);
            log.info("Task {} is recurring; next fire scheduled at {}", task.getId(), nextFire);
        } else {
            // One-shot success (or recurring task past its expiry / cron exhausted)
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(endTime);
        }

        task.setExecutionDurationMs(durationMs);
        task.setLastError(null);
        task.setLastErrorStackTrace(null);
        task.setExecutionResult(result.getResponseData());
        task.setLockedBy(null);
        task.setLockedUntil(null);

        taskRepository.save(task);

        // Update execution log — the just-finished attempt is always COMPLETED here,
        // independent of whether the parent task is one-shot or recurring.
        executionLog.setStatus(TaskStatus.COMPLETED);
        executionLog.setCompletedAt(endTime);
        executionLog.setDurationMs(durationMs);
        executionLog.setSuccess(true);
        executionLog.setResponsePayload(result.getResponseData());
        executionLog.setHttpStatusCode(result.getHttpStatusCode());
        executionLogRepository.save(executionLog);
    }

    /**
     * Compute the next fire time for a recurring task, or null if the task is
     * one-shot, the cron expression is exhausted, or the next fire would be past
     * the task's {@code expiresAt}. The cron is evaluated in UTC; the entity's
     * {@code scheduledTime} is an {@code Instant}, so a UTC interpretation keeps
     * the cron's wall-clock semantics consistent across instances.
     */
    private Instant computeNextCronFire(ScheduledTask task, Instant from) {
        var expr = task.getCronExpression();
        if (expr == null || expr.isBlank()) {
            return null;
        }

        CronExpression cron;
        try {
            cron = CronExpression.parse(expr);
        } catch (IllegalArgumentException e) {
            // Validated at create time, but if it slipped through (legacy row),
            // log and fall back to terminal COMPLETED rather than crashing.
            log.warn("Task {} has invalid cron expression '{}'; treating as one-shot: {}",
                    task.getId(), expr, e.getMessage());
            return null;
        }

        var next = cron.next(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
        if (next == null) {
            return null;
        }
        var nextInstant = next.toInstant(ZoneOffset.UTC);
        if (task.getExpiresAt() != null && !nextInstant.isBefore(task.getExpiresAt())) {
            return null;
        }
        return nextInstant;
    }

    private void handleFailure(ScheduledTask task, TaskExecutionLog executionLog, TaskExecutionResult result, Instant endTime, long durationMs) {
        log.warn("Task {} failed: {}", task.getId(), result.getErrorMessage());

        // Update execution log first
        executionLog.setStatus(TaskStatus.FAILED);
        executionLog.setCompletedAt(endTime);
        executionLog.setDurationMs(durationMs);
        executionLog.setSuccess(false);
        executionLog.setErrorMessage(result.getErrorMessage());
        executionLog.setErrorStackTrace(result.getStackTrace());
        executionLog.setErrorType(result.getErrorType());
        executionLog.setHttpStatusCode(result.getHttpStatusCode());
        executionLog.setResponsePayload(result.getResponseData());
        executionLogRepository.save(executionLog);

        // Check if retryable
        if (!result.isRetryable()) {
            handlePermanentFailure(task, executionLog, result.getErrorType(), result.getErrorMessage());
            return;
        }

        // Check retry count
        var newRetryCount = task.getRetryCount() + 1;
        var maxRetries = task.getEffectiveMaxRetries(properties.getDefaultMaxRetries());

        if (newRetryCount >= maxRetries) {
            handleMaxRetriesExceeded(task, result);
            return;
        }

        // Schedule retry
        scheduleRetry(task, result, newRetryCount);
    }

    private void handlePermanentFailure(ScheduledTask task, TaskExecutionLog executionLog, String errorType, String errorMessage) {
        log.error("Task {} failed permanently (non-retryable): {}", task.getId(), errorMessage);

        task.setStatus(TaskStatus.DEAD_LETTER);
        task.setCompletedAt(Instant.now());
        task.setLastError(errorMessage);
        task.setLockedBy(null);
        task.setLockedUntil(null);

        taskRepository.save(task);

        // Send alert for permanent failures on critical tasks
        slackAlertService.sendTaskFailureAlert(task, errorMessage);
    }

    private void handleMaxRetriesExceeded(ScheduledTask task, TaskExecutionResult result) {
        log.error("Task {} exceeded max retries ({})", task.getId(), task.getRetryCount());

        task.setStatus(TaskStatus.MAX_RETRIES_EXCEEDED);
        task.setCompletedAt(Instant.now());
        task.setLastError(result.getErrorMessage());
        task.setLastErrorStackTrace(result.getStackTrace());
        task.setLockedBy(null);
        task.setLockedUntil(null);

        taskRepository.save(task);

        // Record metric
        metricsConfig.recordMaxRetriesExceeded(task.getTaskType());

        // Send Slack alert
        slackAlertService.sendMaxRetriesExceededAlert(task);
    }

    private void scheduleRetry(ScheduledTask task, TaskExecutionResult result, int newRetryCount) {
        // Calculate next retry time
        long delayMs;
        if (result.getCustomRetryDelayMs() != null) {
            delayMs = result.getCustomRetryDelayMs();
        } else {
            var handler = handlerRegistry.getHandlerOrThrow(task.getTaskType());
            delayMs = handler.calculateNextRetryDelayMs(task, properties.getDefaultRetryDelayHours());
        }

        var nextRetryTime = Instant.now().plusMillis(delayMs);

        log.info("Scheduling retry {} for task {} at {}", newRetryCount, task.getId(), nextRetryTime);

        task.setStatus(TaskStatus.RETRY_PENDING);
        task.setRetryCount(newRetryCount);
        task.setScheduledTime(nextRetryTime);
        task.setLastError(result.getErrorMessage());
        task.setLastErrorStackTrace(result.getStackTrace());
        task.setLockedBy(null);
        task.setLockedUntil(null);

        taskRepository.save(task);

        // Record retry metric
        metricsConfig.recordRetry(task.getTaskType(), newRetryCount);
    }

    private TaskExecutionLog createExecutionLog(ScheduledTask task, Instant startTime) {
        var log = TaskExecutionLog.builder()
                .taskId(task.getId())
                .attemptNumber(task.getRetryCount() + 1)
                .status(TaskStatus.PROCESSING)
                .executorInstance(getInstanceId())
                .startedAt(startTime)
                .success(false)
                .requestPayload(buildRequestPayload(task))
                .build();

        return executionLogRepository.save(log);
    }

    private Map<String, Object> buildRequestPayload(ScheduledTask task) {
        var payload = new HashMap<String, Object>();
        payload.put("taskId", task.getId().toString());
        payload.put("taskType", task.getTaskType().name());
        payload.put("referenceId", task.getReferenceId());
        payload.put("secondaryReferenceId", task.getSecondaryReferenceId());
        payload.put("attemptNumber", task.getRetryCount() + 1);
        if (task.getPayload() != null) {
            payload.put("taskPayload", task.getPayload());
        }
        return payload;
    }
}
