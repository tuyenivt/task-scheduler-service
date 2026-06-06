package com.example.taskscheduler.domain.repository;

import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ScheduledTask entity.
 * <p>
 * Uses PostgreSQL-specific features for efficient distributed task acquisition:
 * - FOR UPDATE SKIP LOCKED for row-level locking
 * - JSONB queries for metadata filtering
 * - Optimistic locking with version field
 */
@Repository
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, UUID> {

    /**
     * Find tasks ready for execution with distributed locking.
     * Uses SKIP LOCKED to prevent multiple instances from picking the same task.
     * <p>
     * Criteria:
     * - Status is executable (PENDING, SCHEDULED, FAILED, RETRY_PENDING)
     * - Scheduled time has passed
     * - Not currently locked OR lock has expired
     * - Not expired
     * <p>
     * Orders by priority (descending) then scheduled time (ascending)
     */
    @Query(value = """
            SELECT t.* FROM scheduled_tasks t
            WHERE t.status IN ('PENDING', 'SCHEDULED', 'FAILED', 'RETRY_PENDING')
              AND t.scheduled_time <= :now
              AND (t.locked_by IS NULL OR t.locked_until < :now)
              AND (t.expires_at IS NULL OR t.expires_at > :now)
            ORDER BY
              CASE t.priority
                WHEN 'CRITICAL' THEN 4
                WHEN 'HIGH' THEN 3
                WHEN 'NORMAL' THEN 2
                WHEN 'LOW' THEN 1
              END DESC,
              t.scheduled_time ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ScheduledTask> findTasksForExecution(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Atomically acquire a lock on a task for processing.
     * <p>
     * The lock predicate (lockedBy IS NULL OR lockedUntil < now) is the single
     * source of truth — at most one caller wins for a given (id, now). The caller
     * should re-read the row via findById within the same transaction to obtain
     * the post-lock state, eliminating the poll/execute version race.
     *
     * @return number of rows updated (1 if lock won, 0 otherwise)
     */
    @Modifying
    @Query("""
            UPDATE ScheduledTask t
            SET t.lockedBy = :instanceId,
                t.lockedUntil = :lockUntil,
                t.status = 'PROCESSING',
                t.startedAt = :now,
                t.updatedAt = :now
            WHERE t.id = :taskId
              AND (t.lockedBy IS NULL OR t.lockedUntil < :now)
            """)
    int acquireTaskLock(
            @Param("taskId") UUID taskId,
            @Param("instanceId") String instanceId,
            @Param("lockUntil") Instant lockUntil,
            @Param("now") Instant now);

    /**
     * Release lock and update task status after execution
     */
    @Modifying
    @Query("""
            UPDATE ScheduledTask t
            SET t.lockedBy = NULL,
                t.lockedUntil = NULL,
                t.status = :status,
                t.completedAt = :completedAt,
                t.executionDurationMs = :durationMs,
                t.lastError = :lastError,
                t.retryCount = :retryCount,
                t.scheduledTime = :nextScheduledTime,
                t.updatedAt = :now
            WHERE t.id = :taskId
              AND t.lockedBy = :instanceId
            """)
    int releaseTaskLock(
            @Param("taskId") UUID taskId,
            @Param("instanceId") String instanceId,
            @Param("status") TaskStatus status,
            @Param("completedAt") Instant completedAt,
            @Param("durationMs") Long durationMs,
            @Param("lastError") String lastError,
            @Param("retryCount") Integer retryCount,
            @Param("nextScheduledTime") Instant nextScheduledTime,
            @Param("now") Instant now);

    /**
     * Emergency lock release for a task whose normal execution path threw
     * after the lock was acquired (and thus committed) in a prior transaction.
     * <p>
     * Only releases if this instance still owns the lock and the task is still
     * in a non-terminal state. Schedules the task for immediate retry so it is
     * picked up on the next polling cycle rather than waiting for the full
     * lock TTL to expire.
     */
    @Modifying
    @Query("""
            UPDATE ScheduledTask t
            SET t.lockedBy = NULL,
                t.lockedUntil = NULL,
                t.status = 'RETRY_PENDING',
                t.scheduledTime = :nextRetryTime,
                t.updatedAt = :now
            WHERE t.id = :taskId
              AND t.lockedBy = :instanceId
              AND t.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED', 'MAX_RETRIES_EXCEEDED', 'DEAD_LETTER')
            """)
    int releaseLockForRetry(
            @Param("taskId") UUID taskId,
            @Param("instanceId") String instanceId,
            @Param("nextRetryTime") Instant nextRetryTime,
            @Param("now") Instant now);

    /**
     * Find stale locked tasks whose lock window expired at least a grace period ago.
     * <p>
     * The caller passes {@code cutoff = now - grace}; rows with
     * {@code lockedUntil < cutoff} are those whose advertised lock window ended
     * more than {@code grace} ago and are therefore safe to treat as abandoned
     * (assuming {@code lockDurationMinutes} strictly exceeds handler worst-case).
     */
    @Query("""
            SELECT t FROM ScheduledTask t
            WHERE t.lockedBy IS NOT NULL
              AND t.lockedUntil < :cutoff
              AND t.status = 'PROCESSING'
            """)
    List<ScheduledTask> findStaleTasks(@Param("cutoff") Instant cutoff);

    /**
     * Reset stale tasks to RETRY_PENDING — only if their lock window is still
     * expired by the grace cutoff at UPDATE time.
     * <p>
     * The {@code lockedUntil < :cutoff} predicate guards against the race where
     * a concurrent executor revives the lock between the {@code findStaleTasks}
     * read and this UPDATE: if the row was just re-locked by a live executor,
     * its {@code lockedUntil} is now in the future and this UPDATE skips it.
     */
    @Modifying
    @Query("""
            UPDATE ScheduledTask t
            SET t.lockedBy = NULL,
                t.lockedUntil = NULL,
                t.status = 'RETRY_PENDING',
                t.lastError = 'Task execution timed out or instance crashed',
                t.scheduledTime = :nextRetryTime,
                t.updatedAt = :now
            WHERE t.id IN :taskIds
              AND t.status = 'PROCESSING'
              AND t.lockedUntil IS NOT NULL
              AND t.lockedUntil < :cutoff
            """)
    int resetStaleTasks(
            @Param("taskIds") List<UUID> taskIds,
            @Param("nextRetryTime") Instant nextRetryTime,
            @Param("now") Instant now,
            @Param("cutoff") Instant cutoff);

    /**
     * Find tasks by reference ID (e.g., order ID)
     */
    List<ScheduledTask> findByReferenceIdOrderByCreatedAtDesc(String referenceId);

    /**
     * Find tasks by reference ID (pageable)
     */
    Page<ScheduledTask> findByReferenceId(String referenceId, Pageable pageable);

    /**
     * Find tasks by reference ID and type
     */
    Page<ScheduledTask> findByReferenceIdAndTaskType(String referenceId, TaskType taskType, Pageable pageable);

    /**
     * Find tasks by reference ID and status
     */
    Page<ScheduledTask> findByReferenceIdAndStatus(String referenceId, TaskStatus status, Pageable pageable);

    /**
     * Find tasks by reference ID, type, and status
     */
    Page<ScheduledTask> findByReferenceIdAndTaskTypeAndStatus(String referenceId, TaskType taskType, TaskStatus status, Pageable pageable);

    /**
     * Find tasks by type and status
     */
    Page<ScheduledTask> findByTaskTypeAndStatus(TaskType taskType, TaskStatus status, Pageable pageable);

    /**
     * Find tasks by status
     */
    Page<ScheduledTask> findByStatus(TaskStatus status, Pageable pageable);

    /**
     * Find tasks by type
     */
    Page<ScheduledTask> findByTaskType(TaskType taskType, Pageable pageable);

    /**
     * Check if a task already exists for a reference
     */
    @Query("""
            SELECT COUNT(t) > 0 FROM ScheduledTask t
            WHERE t.referenceId = :referenceId
              AND t.taskType = :taskType
              AND t.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
            """)
    boolean existsActiveTaskForReference(@Param("referenceId") String referenceId, @Param("taskType") TaskType taskType);

    /**
     * Find existing active task for a reference
     */
    @Query("""
            SELECT t FROM ScheduledTask t
            WHERE t.referenceId = :referenceId
              AND t.taskType = :taskType
              AND t.status NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
            """)
    Optional<ScheduledTask> findActiveTaskForReference(@Param("referenceId") String referenceId, @Param("taskType") TaskType taskType);

    /**
     * Count tasks by status
     */
    long countByStatus(TaskStatus status);

    /**
     * Count tasks by type and status
     */
    long countByTaskTypeAndStatus(TaskType taskType, TaskStatus status);

    /**
     * Find tasks scheduled within a time range
     */
    @Query("""
            SELECT t FROM ScheduledTask t
            WHERE t.scheduledTime BETWEEN :start AND :end
              AND t.status IN :statuses
            ORDER BY t.scheduledTime ASC
            """)
    List<ScheduledTask> findTasksInTimeRange(@Param("start") Instant start, @Param("end") Instant end, @Param("statuses") List<TaskStatus> statuses);

    /**
     * Bulk update status for multiple tasks
     */
    @Modifying
    @Query("""
            UPDATE ScheduledTask t
            SET t.status = :newStatus, t.updatedAt = :now
            WHERE t.id IN :taskIds
            """)
    int bulkUpdateStatus(@Param("taskIds") List<UUID> taskIds, @Param("newStatus") TaskStatus newStatus, @Param("now") Instant now);

    /**
     * Delete old completed tasks (for cleanup)
     */
    @Modifying
    @Query("""
            DELETE FROM ScheduledTask t
            WHERE t.status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
              AND t.completedAt < :cutoff
            """)
    int deleteOldCompletedTasks(@Param("cutoff") Instant cutoff);

    /**
     * Find tasks with metadata filter (PostgreSQL JSONB query)
     */
    @Query(value = """
            SELECT * FROM scheduled_tasks t
            WHERE t.metadata @> CAST(:jsonFilter AS jsonb)
              AND t.status = :status
            """, nativeQuery = true)
    List<ScheduledTask> findByMetadataAndStatus(@Param("jsonFilter") String jsonFilter, @Param("status") String status);

    /**
     * Get task statistics grouped by status
     */
    @Query("""
            SELECT t.status as status, COUNT(t) as count
            FROM ScheduledTask t
            GROUP BY t.status
            """)
    List<Object[]> getTaskStatsByStatus();

    /**
     * Get task statistics grouped by type
     */
    @Query("""
            SELECT t.taskType as taskType, t.status as status, COUNT(t) as count
            FROM ScheduledTask t
            GROUP BY t.taskType, t.status
            """)
    List<Object[]> getTaskStatsByTypeAndStatus();
}
