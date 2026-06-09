package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Retention job that prunes terminal-state task rows and stale execution logs.
 * <p>
 * Both tables are written-once-then-quiet so unbounded growth has no business
 * value and steadily degrades polling-query plans plus the GIN indexes on the
 * JSONB columns. The job is ShedLock-gated so it runs on a single instance
 * per fire even with multiple replicas, and the JDBC batch DELETEs run inside
 * a transaction so a mid-run failure rolls back cleanly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    private final ScheduledTaskRepository taskRepository;
    private final TaskExecutionLogRepository executionLogRepository;
    private final TaskSchedulerProperties properties;

    /**
     * Runs daily at 03:00 server time, when traffic is typically lowest.
     * Deletes terminal-state task rows whose {@code completed_at} is older
     * than the configured retention; the {@code ON DELETE CASCADE} FK on
     * {@code task_execution_logs.task_id} carries their child log rows with
     * them, so explicit log deletion is only needed for logs whose parent task
     * is still inside its retention window but whose own retention has expired.
     * <p>
     * The order matters: logs are deleted second so a logs-only purge cannot
     * outrun the parent task deletion that the CASCADE would otherwise handle.
     */
    @Scheduled(cron = "${task-scheduler.retention-cron:0 0 3 * * *}")
    @SchedulerLock(name = "retentionJob", lockAtLeastFor = "1m", lockAtMostFor = "30m")
    @Transactional
    public void purgeOldRows() {
        var now = Instant.now();

        // Floor log retention to task retention so logs cannot disappear ahead
        // of their parent task rows; with the FK in place this is belt-and-
        // braces against an operator who shortens log retention below task
        // retention by mistake.
        int logRetention = Math.max(properties.getExecutionLogRetentionDays(), properties.getRetentionDays());

        var taskCutoff = now.minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        var logCutoff = now.minus(logRetention, ChronoUnit.DAYS);

        log.info("Retention job starting: taskCutoff={}, logCutoff={}", taskCutoff, logCutoff);

        int taskRows = taskRepository.deleteOldCompletedTasks(taskCutoff);
        int logRows = executionLogRepository.deleteOldLogs(logCutoff);

        log.info("Retention job finished: deleted {} task row(s) and {} execution log row(s)",
                taskRows, logRows);
    }
}
