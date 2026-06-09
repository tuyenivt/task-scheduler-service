package com.example.taskscheduler.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the task scheduler.
 * Loaded from application.yml.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "task-scheduler")
public class TaskSchedulerProperties {

    /**
     * Polling interval in milliseconds for checking pending tasks
     */
    @Min(1000)
    private long pollIntervalMs = 30000;

    /**
     * Maximum number of tasks to fetch per poll cycle
     */
    @Min(1)
    private int batchSize = 100;

    /**
     * Number of concurrent executor threads
     */
    @Min(1)
    private int executorPoolSize = 20;

    /**
     * Bounded queue capacity for the dispatch executor.
     * <p>
     * When the queue is full, the polling thread runs tasks itself
     * (CallerRunsPolicy), naturally throttling further dispatch. Should be
     * roughly {@code batchSize * 2} so a single poll batch can be queued
     * without immediate backpressure under normal load.
     */
    @Min(1)
    private int dispatchQueueCapacity = 200;

    /**
     * Default maximum retry attempts
     */
    @Min(0)
    private int defaultMaxRetries = 5;

    /**
     * Default hours to wait before retry (24 = next day)
     */
    @Min(1)
    private int defaultRetryDelayHours = 24;

    /**
     * Lock duration in minutes.
     * <p>
     * Contract: must strictly exceed any handler's worst-case execution time
     * (including all retries within a single dispatch) plus a safety margin for
     * GC pauses and clock skew. Setting this too low risks resetting an
     * in-flight task and causing duplicate external side effects.
     */
    @Min(1)
    private int lockDurationMinutes = 30;

    /**
     * Threshold in minutes after which a locked task is considered stale.
     * <p>
     * Retained for backward compatibility but no longer drives the abandonment
     * check directly - {@link #staleTaskGraceMinutes} is the authoritative knob.
     */
    @Min(1)
    private int staleTaskThresholdMinutes = 60;

    /**
     * Extra grace period (in minutes) added to {@code lockedUntil} before a row
     * is treated as abandoned by the stale-task cleanup job.
     * <p>
     * A row is reset only when {@code locked_until + grace < now}, i.e. the lock
     * window has been expired for at least {@code grace} minutes. This makes the
     * abandonment check robust to clock skew and short GC pauses on the
     * lock-holder. Combined with the {@link #lockDurationMinutes} contract,
     * resetting a row implies the original holder is almost certainly gone.
     */
    @Min(1)
    private int staleTaskGraceMinutes = 5;

    /**
     * Retention (in days) for terminal-state rows in {@code scheduled_tasks}.
     * Rows older than this whose status is COMPLETED, CANCELLED, or EXPIRED are
     * eligible for deletion by the retention job. Indefinite growth degrades
     * polling-query plans and bloats the GIN indexes on {@code payload} and
     * {@code metadata}.
     */
    @Min(1)
    private int retentionDays = 30;

    /**
     * Retention (in days) for {@code task_execution_logs} rows. Should be
     * <em>at least</em> {@link #retentionDays} so logs are not orphaned ahead
     * of their parent task; the retention job enforces this lower bound at
     * runtime.
     */
    @Min(1)
    private int executionLogRetentionDays = 30;

    /**
     * Retry-delay knobs shared across all task handlers.
     */
    private Retry retry = new Retry();

    /**
     * Centralized retry-jitter configuration.
     * <p>
     * The jitter band is {@code [floor%, ceiling%]} of the base delay - one knob
     * pair per cluster so every handler picks up the same spread and operators
     * can dampen thundering herd globally without editing source.
     */
    @Data
    public static class Retry {
        @Min(0)
        private int jitterFloorPercent = 10;

        @Min(1)
        private int jitterCeilingPercent = 25;
    }
}
