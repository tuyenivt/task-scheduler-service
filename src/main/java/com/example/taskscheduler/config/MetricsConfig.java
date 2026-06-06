package com.example.taskscheduler.config;

import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics configuration for monitoring task scheduler health and performance.
 * <p>
 * Exposes Prometheus metrics for:
 * - Task counts by status and type
 * - Execution times
 * - Error rates
 * - Queue depths
 */
@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final ScheduledTaskRepository taskRepository;

    // Atomic counters for gauge metrics
    private final ConcurrentHashMap<String, AtomicLong> taskCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void initializeMetrics() {
        // Register gauges for each task status
        for (var status : TaskStatus.values()) {
            var key = "status_" + status.name().toLowerCase();
            taskCounters.put(key, new AtomicLong(0));

            Gauge.builder("task_scheduler_tasks", taskCounters.get(key), AtomicLong::get)
                    .tag("status", status.name().toLowerCase())
                    .description("Number of tasks by status")
                    .register(meterRegistry);
        }

        // Register gauges for each task type
        for (var type : TaskType.values()) {
            var key = "type_" + type.name().toLowerCase();
            taskCounters.put(key, new AtomicLong(0));

            Gauge.builder("task_scheduler_tasks_by_type", taskCounters.get(key), AtomicLong::get)
                    .tag("type", type.name().toLowerCase())
                    .description("Number of tasks by type")
                    .register(meterRegistry);
        }

        // Queue depth gauge
        taskCounters.put("queue_depth", new AtomicLong(0));
        Gauge.builder("task_scheduler_queue_depth", taskCounters.get("queue_depth"), AtomicLong::get)
                .description("Number of pending executable tasks")
                .register(meterRegistry);
    }

    /**
     * Periodically update gauge metrics from the database.
     * <p>
     * A single grouped query populates every per-status gauge and the queue
     * depth — collapsing what used to be one query per status (~12 calls per
     * tick) into one. Statuses absent from the result default to zero so
     * gauges decay correctly when a status empties out.
     */
    @Scheduled(fixedDelayString = "${task-scheduler.metrics-update-interval-ms:60000}")
    public void updateMetrics() {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        for (var row : taskRepository.getTaskStatsByStatus()) {
            counts.put((TaskStatus) row[0], (Long) row[1]);
        }

        for (var status : TaskStatus.values()) {
            var key = "status_" + status.name().toLowerCase();
            taskCounters.get(key).set(counts.getOrDefault(status, 0L));
        }

        var queueDepth = counts.getOrDefault(TaskStatus.PENDING, 0L)
                + counts.getOrDefault(TaskStatus.RETRY_PENDING, 0L)
                + counts.getOrDefault(TaskStatus.SCHEDULED, 0L);
        taskCounters.get("queue_depth").set(queueDepth);
    }

    /**
     * Create a timer for task execution
     */
    public Timer.Sample startTaskExecutionTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Record task execution time
     */
    public void recordTaskExecution(Timer.Sample sample, TaskType taskType, boolean success) {
        sample.stop(Timer.builder("task_scheduler_execution_time")
                .tag("type", taskType.name().toLowerCase())
                .tag("success", String.valueOf(success))
                .description("Task execution time")
                .register(meterRegistry));
    }

    /**
     * Increment task counter
     */
    public void incrementTaskCounter(String name, String... tags) {
        meterRegistry.counter("task_scheduler_" + name, tags).increment();
    }

    /**
     * Record task failure
     */
    public void recordTaskFailure(TaskType taskType, String errorType) {
        meterRegistry.counter("task_scheduler_failures",
                "type", taskType.name().toLowerCase(),
                "error_type", errorType != null ? errorType : "unknown"
        ).increment();
    }

    /**
     * Record retry
     */
    public void recordRetry(TaskType taskType, int attemptNumber) {
        meterRegistry.counter("task_scheduler_retries",
                "type", taskType.name().toLowerCase(),
                "attempt", String.valueOf(attemptNumber)
        ).increment();
    }

    /**
     * Record max retries exceeded alert
     */
    public void recordMaxRetriesExceeded(TaskType taskType) {
        meterRegistry.counter("task_scheduler_max_retries_exceeded",
                "type", taskType.name().toLowerCase()
        ).increment();
    }
}
