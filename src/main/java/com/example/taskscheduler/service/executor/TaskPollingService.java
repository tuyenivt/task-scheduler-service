package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service responsible for polling pending tasks and dispatching them for execution.
 * <p>
 * Uses ShedLock to ensure only one instance polls at a time across the EKS cluster.
 * Individual task locking uses PostgreSQL's SKIP LOCKED for efficient distribution.
 * <p>
 * Flow:
 * 1. Poll job runs on schedule (e.g., every 30 seconds)
 * 2. Fetches batch of ready tasks using FOR UPDATE SKIP LOCKED
 * 3. Dispatches each task to the virtual thread executor
 * 4. Each task execution handles its own locking and updates
 */
@Slf4j
@Service
public class TaskPollingService {

    private final ScheduledTaskRepository taskRepository;
    private final TaskExecutorService taskExecutorService;
    private final TaskSchedulerProperties properties;
    private final ExecutorService virtualThreadExecutor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public TaskPollingService(ScheduledTaskRepository taskRepository, TaskExecutorService taskExecutorService, TaskSchedulerProperties properties,
                              @Qualifier("virtualThreadExecutor") ExecutorService virtualThreadExecutor) {
        this.taskRepository = taskRepository;
        this.taskExecutorService = taskExecutorService;
        this.properties = properties;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * Main polling job - fetches and processes pending tasks.
     * <p>
     * ShedLock ensures this only runs on one instance at a time,
     * but the actual task processing is distributed across all instances
     * through the SKIP LOCKED mechanism.
     */
    @PreDestroy
    void shutdown() {
        log.info("Shutting down task polling service, waiting for in-flight tasks...");
        shuttingDown.set(true);
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Forced shutdown of virtual thread executor after 30s timeout");
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
        log.info("Task polling service shut down");
    }

    @Scheduled(fixedDelayString = "${task-scheduler.poll-interval-ms:30000}")
    @SchedulerLock(name = "taskPollingJob", lockAtLeastFor = "10s", lockAtMostFor = "5m")
    public void pollAndProcessTasks() {
        if (shuttingDown.get()) {
            log.info("Shutdown in progress, skipping poll cycle");
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.debug("Previous polling cycle still running, skipping");
            return;
        }

        try {
            log.debug("Starting task polling cycle");
            var now = Instant.now();

            // Fetch batch of ready tasks
            var tasks = taskRepository.findTasksForExecution(now, properties.getBatchSize());

            if (tasks.isEmpty()) {
                log.debug("No tasks ready for execution");
                return;
            }

            log.info("Found {} tasks ready for execution", tasks.size());

            // Dispatch tasks to virtual thread executor
            var futures = tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(
                            () -> processTask(task),
                            virtualThreadExecutor
                    )).toList();

            // Wait for all tasks to complete (with timeout)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(properties.getLockDurationMinutes(), java.util.concurrent.TimeUnit.MINUTES)
                    .exceptionally(ex -> {
                        log.error("Error waiting for task completion: {}", ex.getMessage());
                        return null;
                    })
                    .join();

            // Count successes
            var successCount = futures.stream()
                    .filter(f -> {
                        try {
                            return f.isDone() && !f.isCompletedExceptionally() && f.get();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();

            log.info("Completed processing {} tasks, {} successful", tasks.size(), successCount);
        } catch (Exception e) {
            log.error("Error in task polling cycle: {}", e.getMessage(), e);
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Process a single task - acquire lock and execute the locked snapshot.
     * <p>
     * The polled task carries only an ID hint; the authoritative state is
     * whatever {@code acquireLockAndFetch} returns under the atomic UPDATE,
     * so we never execute against the stale poll-time snapshot.
     */
    private boolean processTask(ScheduledTask task) {
        var taskId = task.getId();

        try {
            var locked = taskExecutorService.acquireLockAndFetch(taskId);
            if (locked.isEmpty()) {
                log.debug("Failed to acquire lock for task {}, skipping", taskId);
                return false;
            }

            return taskExecutorService.executeTask(locked.get());
        } catch (Exception e) {
            log.error("Error processing task {}: {}", taskId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Cleanup stale tasks - reset rows whose lock window expired at least a
     * grace period ago, so the original lock-holder is almost certainly gone.
     * <p>
     * Abandonment is proved by two layers:
     * <ol>
     *   <li>The {@code lockDurationMinutes} contract: it must strictly exceed
     *       any handler's worst-case execution time. A live handler will renew
     *       (via task completion) before its lock window ends.</li>
     *   <li>An extra {@code staleTaskGraceMinutes} buffer beyond the lock
     *       window to tolerate clock skew and short GC pauses.</li>
     * </ol>
     * The conditional UPDATE inside {@code resetStaleTasks} additionally guards
     * against a concurrent executor re-acquiring the lock between the read and
     * the reset.
     */
    @Scheduled(fixedDelayString = "${task-scheduler.stale-task-check-interval-ms:300000}")
    @SchedulerLock(name = "staleTaskCleanup", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void cleanupStaleTasks() {
        try {
            var now = Instant.now();
            var cutoff = now.minusSeconds(properties.getStaleTaskGraceMinutes() * 60L);

            var staleTasks = taskRepository.findStaleTasks(cutoff);

            if (staleTasks.isEmpty()) {
                log.debug("No stale tasks found");
                return;
            }

            log.warn("Found {} candidate stale tasks (lock expired before {}), attempting reset",
                    staleTasks.size(), cutoff);

            var taskIds = staleTasks.stream().map(ScheduledTask::getId).toList();
            var nextRetryTime = now.plusSeconds(60);

            var resetCount = taskRepository.resetStaleTasks(taskIds, nextRetryTime, now, cutoff);
            var skipped = staleTasks.size() - resetCount;
            if (skipped > 0) {
                log.info("Reset {} stale tasks for retry; {} skipped (lock re-acquired concurrently)",
                        resetCount, skipped);
            } else {
                log.info("Reset {} stale tasks for retry", resetCount);
            }
        } catch (Exception e) {
            log.error("Error cleaning up stale tasks: {}", e.getMessage(), e);
        }
    }

    /**
     * Process a specific task immediately (for manual triggers)
     */
    public CompletableFuture<Boolean> processTaskAsync(UUID taskId) {
        return CompletableFuture.supplyAsync(() -> {
            var task = taskRepository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            return processTask(task);
        }, virtualThreadExecutor);
    }
}
