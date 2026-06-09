package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.MetricsConfig;
import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.entity.TaskExecutionLog;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.service.alert.SlackAlertService;
import com.example.taskscheduler.service.handler.TaskExecutionResult;
import com.example.taskscheduler.service.handler.TaskHandler;
import com.example.taskscheduler.service.handler.TaskHandlerRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskExecutorService Tests")
class TaskExecutorServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private TaskExecutionLogRepository executionLogRepository;

    @Mock
    private TaskHandlerRegistry handlerRegistry;

    @Mock
    private SlackAlertService slackAlertService;

    @Mock
    private MetricsConfig metricsConfig;

    @Mock
    private TaskSchedulerProperties properties;

    private TaskExecutorService taskExecutorService;

    @Captor
    private ArgumentCaptor<ScheduledTask> taskCaptor;

    @Captor
    private ArgumentCaptor<TaskExecutionLog> logCaptor;

    private UUID testTaskId;
    private ScheduledTask testTask;
    private TaskHandler mockHandler;
    private Timer.Sample mockTimerSample;

    @BeforeEach
    void setUp() {
        // Construct manually so we can wire `self` to the real instance and
        // exercise transactional boundaries (no Spring AOP proxy in unit tests).
        taskExecutorService = new TaskExecutorService(
                taskRepository,
                executionLogRepository,
                handlerRegistry,
                slackAlertService,
                metricsConfig,
                properties,
                null);
        ReflectionTestUtils.setField(taskExecutorService, "self", taskExecutorService);

        // Trigger @PostConstruct manually since Mockito doesn't call it
        taskExecutorService.initInstanceId();

        testTaskId = UUID.randomUUID();

        testTask = ScheduledTask.builder()
                .id(testTaskId)
                .taskType(TaskType.ORDER_CANCEL)
                .status(TaskStatus.PROCESSING)
                .priority(TaskPriority.NORMAL)
                .referenceId("ORD-12345")
                .scheduledTime(Instant.now().minusSeconds(60))
                .retryCount(0)
                .version(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        mockHandler = mock(TaskHandler.class);
        mockTimerSample = mock(Timer.Sample.class);
    }

    @Nested
    @DisplayName("executeTask Tests")
    class ExecuteTaskTests {

        @Test
        @DisplayName("Should execute task successfully")
        void shouldExecuteTaskSuccessfully() {
            // Given
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(
                    TaskExecutionResult.success(Map.of("orderId", "ORD-12345")));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isTrue();
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(saved.getCompletedAt()).isNotNull();
            assertThat(saved.getLockedBy()).isNull();
            assertThat(saved.getLockedUntil()).isNull();

            verify(metricsConfig).recordTaskExecution(eq(mockTimerSample), eq(TaskType.ORDER_CANCEL), eq(true));
        }

        @Test
        @DisplayName("Should reschedule recurring task to SCHEDULED with next fire time")
        void shouldRescheduleRecurringTaskOnSuccess() {
            // Given a task with a daily cron
            testTask.setCronExpression("0 0 0 * * *");
            testTask.setRetryCount(3);
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(TaskExecutionResult.success(Map.of()));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then: status is SCHEDULED, retryCount reset, scheduledTime advanced
            assertThat(result).isTrue();
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.SCHEDULED);
            assertThat(saved.getRetryCount()).isZero();
            assertThat(saved.getScheduledTime()).isAfter(Instant.now());
            assertThat(saved.getCompletedAt()).isNull();
            assertThat(saved.getLockedBy()).isNull();
        }

        @Test
        @DisplayName("Should complete recurring task when next fire is past expiresAt")
        void shouldCompleteRecurringTaskWhenExpired() {
            // Given: daily cron with expiresAt set to "now + 60s" - execution proceeds
            // (canExecute passes) but the next fire (next midnight UTC) is well past
            // expiresAt, so the executor falls through to COMPLETED rather than
            // SCHEDULED.
            testTask.setCronExpression("0 0 0 * * *");
            testTask.setExpiresAt(Instant.now().plusSeconds(60));
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(TaskExecutionResult.success(Map.of()));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isTrue();
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should handle task failure with retry")
        void shouldHandleTaskFailureWithRetry() {
            // Given
            when(properties.getDefaultMaxRetries()).thenReturn(5);
            when(properties.getDefaultRetryDelayHours()).thenReturn(24);
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(
                    TaskExecutionResult.failure("Service unavailable", "HTTP_503"));
            when(mockHandler.calculateNextRetryDelayMs(any(), anyInt())).thenReturn(3600000L);
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.RETRY_PENDING);
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getScheduledTime()).isAfter(Instant.now().minusSeconds(1));
            assertThat(saved.getLockedBy()).isNull();
        }

        @Test
        @DisplayName("Should handle permanent failure (non-retryable)")
        void shouldHandlePermanentFailure() {
            // Given
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(
                    TaskExecutionResult.permanentFailure("Order not found", "ORDER_NOT_FOUND"));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.DEAD_LETTER);
            assertThat(saved.getCompletedAt()).isNotNull();
            verify(slackAlertService).sendTaskFailureAlert(any(), anyString());
        }

        @Test
        @DisplayName("Should handle max retries exceeded")
        void shouldHandleMaxRetriesExceeded() {
            // Given
            testTask.setRetryCount(4); // Already retried 4 times
            when(properties.getDefaultMaxRetries()).thenReturn(5);
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(
                    TaskExecutionResult.failure("Timeout", "TIMEOUT"));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.MAX_RETRIES_EXCEEDED);
            verify(slackAlertService).sendMaxRetriesExceededAlert(any());
            verify(metricsConfig).recordMaxRetriesExceeded(TaskType.ORDER_CANCEL);
        }

        @Test
        @DisplayName("Should mark expired task")
        void shouldMarkExpiredTask() {
            // Given
            testTask.setExpiresAt(Instant.now().minusSeconds(3600)); // expired 1 hour ago
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(taskRepository).save(taskCaptor.capture());
            assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.EXPIRED);
        }

        @Test
        @DisplayName("Should handle validation failure")
        void shouldHandleValidationFailure() {
            // Given
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            doThrow(new IllegalArgumentException("Missing required field"))
                    .when(mockHandler).validate(any());
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(taskRepository, atLeastOnce()).save(taskCaptor.capture());
            ScheduledTask saved = taskCaptor.getValue();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.DEAD_LETTER);
        }

        @Test
        @DisplayName("Should handle unexpected exception during execution")
        void shouldHandleUnexpectedException() {
            // Given
            when(properties.getDefaultMaxRetries()).thenReturn(5);
            when(properties.getDefaultRetryDelayHours()).thenReturn(24);
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenThrow(new RuntimeException("Unexpected NPE"));
            when(mockHandler.calculateNextRetryDelayMs(any(), anyInt())).thenReturn(3600000L);
            when(taskRepository.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then
            assertThat(result).isFalse();
            verify(metricsConfig).recordTaskExecution(eq(mockTimerSample), eq(TaskType.ORDER_CANCEL), eq(false));
            verify(metricsConfig).recordTaskFailure(eq(TaskType.ORDER_CANCEL), eq("RuntimeException"));
        }

        @Test
        @DisplayName("Should emergency-release the lock when the failure-handling save also throws")
        void shouldEmergencyReleaseLockWhenFailurePathThrows() {
            // Given: the handler fails, then handleFailure's save() also throws,
            // simulating a DB blip during the retry-scheduling write.
            when(properties.getDefaultMaxRetries()).thenReturn(5);
            when(properties.getDefaultRetryDelayHours()).thenReturn(24);
            when(metricsConfig.startTaskExecutionTimer()).thenReturn(mockTimerSample);
            when(executionLogRepository.save(any(TaskExecutionLog.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(mockHandler);
            when(mockHandler.execute(any())).thenReturn(
                    TaskExecutionResult.failure("Service unavailable", "HTTP_503"));
            when(mockHandler.calculateNextRetryDelayMs(any(), anyInt())).thenReturn(3600000L);
            when(taskRepository.save(any(ScheduledTask.class)))
                    .thenThrow(new RuntimeException("DB connection lost"));
            when(taskRepository.releaseLockForRetry(eq(testTaskId), anyString(), any(), any()))
                    .thenReturn(1);

            // When
            boolean result = taskExecutorService.executeTask(testTask);

            // Then: emergency release was invoked exactly once
            assertThat(result).isFalse();
            verify(taskRepository).releaseLockForRetry(eq(testTaskId), anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("acquireLockAndFetch Tests")
    class AcquireLockAndFetchTests {

        @Test
        @DisplayName("Should acquire lock and return post-lock snapshot")
        void shouldAcquireLockSuccessfully() {
            // Given
            when(properties.getLockDuration()).thenReturn(java.time.Duration.ofMinutes(30));
            when(taskRepository.acquireTaskLock(eq(testTaskId), anyString(), any(), any()))
                    .thenReturn(1);
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));

            // When
            Optional<ScheduledTask> result = taskExecutorService.acquireLockAndFetch(testTaskId);

            // Then
            assertThat(result).contains(testTask);
        }

        @Test
        @DisplayName("Should return empty when atomic UPDATE updates zero rows")
        void shouldFailToAcquireLockWhenAlreadyLocked() {
            // Given
            when(properties.getLockDuration()).thenReturn(java.time.Duration.ofMinutes(30));
            when(taskRepository.acquireTaskLock(eq(testTaskId), anyString(), any(), any()))
                    .thenReturn(0);

            // When
            Optional<ScheduledTask> result = taskExecutorService.acquireLockAndFetch(testTaskId);

            // Then
            assertThat(result).isEmpty();
            verify(taskRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Should return empty when lock won but row disappeared before re-read")
        void shouldReturnEmptyWhenRowDisappearsAfterLock() {
            // Given
            when(properties.getLockDuration()).thenReturn(java.time.Duration.ofMinutes(30));
            when(taskRepository.acquireTaskLock(eq(testTaskId), anyString(), any(), any()))
                    .thenReturn(1);
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.empty());

            // When
            Optional<ScheduledTask> result = taskExecutorService.acquireLockAndFetch(testTaskId);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
