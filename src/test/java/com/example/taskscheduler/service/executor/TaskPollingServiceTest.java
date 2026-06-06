package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskPollingService Tests")
class TaskPollingServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private TaskExecutorService taskExecutorService;

    @Mock
    private TaskSchedulerProperties properties;

    private TaskPollingService taskPollingService;

    private ScheduledTask testTask;

    @BeforeEach
    void setUp() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        taskPollingService = new TaskPollingService(
                taskRepository, taskExecutorService, properties, executor);

        testTask = ScheduledTask.builder()
                .id(UUID.randomUUID())
                .taskType(TaskType.ORDER_CANCEL)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId("ORD-12345")
                .scheduledTime(Instant.now().minusSeconds(60))
                .retryCount(0)
                .version(0L)
                .build();
    }

    @Nested
    @DisplayName("pollAndProcessTasks Tests")
    class PollAndProcessTasksTests {

        @Test
        @DisplayName("Should poll and dispatch tasks for execution")
        void shouldPollAndDispatchTasks() {
            // Given
            when(properties.getBatchSize()).thenReturn(100);
            when(properties.getLockDurationMinutes()).thenReturn(30);
            when(taskRepository.findTasksForExecution(any(Instant.class), eq(100)))
                    .thenReturn(List.of(testTask));
            when(taskExecutorService.acquireLockAndFetch(eq(testTask.getId())))
                    .thenReturn(Optional.of(testTask));
            when(taskExecutorService.executeTask(any())).thenReturn(true);

            // When
            taskPollingService.pollAndProcessTasks();

            // Then - allow async execution to complete
            verify(taskRepository).findTasksForExecution(any(Instant.class), eq(100));
            // The task should be submitted to executor - verify with timeout for async
            verify(taskExecutorService, timeout(5000)).acquireLockAndFetch(eq(testTask.getId()));
        }

        @Test
        @DisplayName("Should handle empty task list gracefully")
        void shouldHandleEmptyTaskList() {
            // Given
            when(properties.getBatchSize()).thenReturn(100);
            when(taskRepository.findTasksForExecution(any(Instant.class), eq(100)))
                    .thenReturn(List.of());

            // When
            taskPollingService.pollAndProcessTasks();

            // Then
            verify(taskExecutorService, never()).acquireLockAndFetch(any());
            verify(taskExecutorService, never()).executeTask(any());
        }

        @Test
        @DisplayName("Should skip task when lock acquisition fails")
        void shouldSkipTaskWhenLockFails() {
            // Given
            when(properties.getBatchSize()).thenReturn(100);
            when(properties.getLockDurationMinutes()).thenReturn(30);
            when(taskRepository.findTasksForExecution(any(Instant.class), eq(100)))
                    .thenReturn(List.of(testTask));
            when(taskExecutorService.acquireLockAndFetch(eq(testTask.getId())))
                    .thenReturn(Optional.empty());

            // When
            taskPollingService.pollAndProcessTasks();

            // Then
            verify(taskExecutorService, timeout(5000)).acquireLockAndFetch(eq(testTask.getId()));
            verify(taskExecutorService, never()).executeTask(any());
        }
    }

    @Nested
    @DisplayName("cleanupStaleTasks Tests")
    class CleanupStaleTasksTests {

        @Test
        @DisplayName("Should reset stale tasks")
        void shouldResetStaleTasks() {
            // Given
            when(properties.getStaleTaskGraceMinutes()).thenReturn(5);
            var staleTask = ScheduledTask.builder()
                    .id(UUID.randomUUID())
                    .taskType(TaskType.PAYMENT_REFUND)
                    .status(TaskStatus.PROCESSING)
                    .lockedBy("crashed-instance")
                    .lockedUntil(Instant.now().minusSeconds(7200))
                    .build();

            when(taskRepository.findStaleTasks(any(Instant.class)))
                    .thenReturn(List.of(staleTask));
            when(taskRepository.resetStaleTasks(anyList(), any(Instant.class), any(Instant.class), any(Instant.class)))
                    .thenReturn(1);

            // When
            taskPollingService.cleanupStaleTasks();

            // Then
            verify(taskRepository).resetStaleTasks(anyList(), any(Instant.class), any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("Should skip when concurrent executor re-acquired the lock")
        void shouldReportSkippedWhenLockRaced() {
            // Given a stale candidate, but the conditional UPDATE returns 0 because
            // the lock was re-acquired between the read and the reset.
            when(properties.getStaleTaskGraceMinutes()).thenReturn(5);
            var staleTask = ScheduledTask.builder()
                    .id(UUID.randomUUID())
                    .taskType(TaskType.PAYMENT_REFUND)
                    .status(TaskStatus.PROCESSING)
                    .lockedBy("crashed-instance")
                    .lockedUntil(Instant.now().minusSeconds(7200))
                    .build();

            when(taskRepository.findStaleTasks(any(Instant.class)))
                    .thenReturn(List.of(staleTask));
            when(taskRepository.resetStaleTasks(anyList(), any(Instant.class), any(Instant.class), any(Instant.class)))
                    .thenReturn(0);

            // When
            taskPollingService.cleanupStaleTasks();

            // Then - no exception thrown, reset still attempted
            verify(taskRepository).resetStaleTasks(anyList(), any(Instant.class), any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("Should handle no stale tasks")
        void shouldHandleNoStaleTasks() {
            // Given
            when(properties.getStaleTaskGraceMinutes()).thenReturn(5);
            when(taskRepository.findStaleTasks(any(Instant.class))).thenReturn(List.of());

            // When
            taskPollingService.cleanupStaleTasks();

            // Then
            verify(taskRepository, never()).resetStaleTasks(anyList(), any(), any(), any());
        }
    }
}
