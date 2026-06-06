package com.example.taskscheduler.service;

import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.dto.CreateTaskRequest;
import com.example.taskscheduler.dto.TaskResponse;
import com.example.taskscheduler.exception.DuplicateTaskException;
import com.example.taskscheduler.exception.InvalidTaskStateException;
import com.example.taskscheduler.exception.TaskNotFoundException;
import com.example.taskscheduler.mapper.TaskMapper;
import com.example.taskscheduler.service.executor.TaskPollingService;
import com.example.taskscheduler.service.handler.TaskHandler;
import com.example.taskscheduler.service.handler.TaskHandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskManagementService Tests")
class TaskManagementServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private TaskExecutionLogRepository executionLogRepository;

    @Mock
    private TaskPollingService taskPollingService;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskHandlerRegistry handlerRegistry;

    @Mock
    private TaskHandler taskHandler;

    @InjectMocks
    private TaskManagementService taskManagementService;

    @Captor
    private ArgumentCaptor<ScheduledTask> taskCaptor;

    private UUID testTaskId;
    private ScheduledTask testTask;
    private TaskResponse testTaskResponse;

    @BeforeEach
    void setUp() {
        testTaskId = UUID.randomUUID();

        testTask = ScheduledTask.builder()
                .id(testTaskId)
                .taskType(TaskType.ORDER_CANCEL)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId("ORD-12345")
                .description("Test order cancellation")
                .scheduledTime(Instant.now())
                .retryCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testTaskResponse = TaskResponse.builder()
                .id(testTaskId)
                .taskType(TaskType.ORDER_CANCEL)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId("ORD-12345")
                .build();
    }

    @Nested
    @DisplayName("Task Creation Tests")
    class TaskCreationTests {

        @Test
        @DisplayName("Should create task successfully")
        void shouldCreateTaskSuccessfully() {
            // Given
            CreateTaskRequest request = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-12345")
                    .description("Cancel order")
                    .priority(TaskPriority.HIGH)
                    .payload(Map.of("reason", "Customer request"))
                    .preventDuplicates(false)
                    .build();

            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(taskHandler);
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            TaskResponse response = taskManagementService.createTask(request);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getTaskType()).isEqualTo(TaskType.ORDER_CANCEL);
            assertThat(response.getReferenceId()).isEqualTo("ORD-12345");

            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask savedTask = taskCaptor.getValue();
            assertThat(savedTask.getTaskType()).isEqualTo(TaskType.ORDER_CANCEL);
            assertThat(savedTask.getPriority()).isEqualTo(TaskPriority.HIGH);
            assertThat(savedTask.getPayload()).containsEntry("reason", "Customer request");
        }

        @Test
        @DisplayName("Should throw DuplicateTaskException when duplicate detected")
        void shouldThrowDuplicateTaskException() {
            // Given
            CreateTaskRequest request = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-12345")
                    .preventDuplicates(true)
                    .build();

            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(taskHandler);
            when(taskRepository.existsActiveTaskForReference("ORD-12345", TaskType.ORDER_CANCEL))
                    .thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> taskManagementService.createTask(request))
                    .isInstanceOf(DuplicateTaskException.class);
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should reject creation when cron expression is invalid")
        void shouldRejectInvalidCronExpression() {
            // Given
            CreateTaskRequest request = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-CRON")
                    .cronExpression("not a cron")
                    .preventDuplicates(false)
                    .build();

            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(taskHandler);

            // When/Then
            assertThatThrownBy(() -> taskManagementService.createTask(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid cron expression");
            verify(taskRepository, never()).save(any());
            verify(taskRepository, never()).existsActiveTaskForReference(any(), any());
        }

        @Test
        @DisplayName("Should reject creation when handler validation throws")
        void shouldRejectWhenHandlerValidationFails() {
            // Given
            CreateTaskRequest request = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-BAD")
                    .preventDuplicates(false)
                    .build();

            when(handlerRegistry.getHandlerOrThrow(TaskType.ORDER_CANCEL)).thenReturn(taskHandler);
            doThrow(new IllegalArgumentException("missing required payload key: reason"))
                    .when(taskHandler).validateForCreate(request);

            // When/Then
            assertThatThrownBy(() -> taskManagementService.createTask(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing required payload key");
            verify(taskRepository, never()).save(any());
            verify(taskRepository, never()).existsActiveTaskForReference(any(), any());
        }

        @Test
        @DisplayName("Should set default values for optional fields")
        void shouldSetDefaultValues() {
            // Given
            CreateTaskRequest request = CreateTaskRequest.builder()
                    .taskType(TaskType.PAYMENT_REFUND)
                    .referenceId("PAY-67890")
                    .build();

            when(handlerRegistry.getHandlerOrThrow(TaskType.PAYMENT_REFUND)).thenReturn(taskHandler);
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            taskManagementService.createTask(request);

            // Then
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask savedTask = taskCaptor.getValue();
            assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(savedTask.getPriority()).isEqualTo(TaskPriority.NORMAL);
            assertThat(savedTask.getScheduledTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Task Status Management Tests")
    class TaskStatusManagementTests {

        @Test
        @DisplayName("Should cancel pending task")
        void shouldCancelPendingTask() {
            // Given
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            TaskResponse response = taskManagementService.cancelTask(testTaskId, "Test cancellation");

            // Then
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask savedTask = taskCaptor.getValue();
            assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(savedTask.getLastError()).contains("Test cancellation");
        }

        @Test
        @DisplayName("Should not cancel task in terminal state")
        void shouldNotCancelTerminalTask() {
            // Given
            testTask.setStatus(TaskStatus.COMPLETED);
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));

            // When/Then
            assertThatThrownBy(() -> taskManagementService.cancelTask(testTaskId, "reason"))
                    .isInstanceOf(InvalidTaskStateException.class);
        }

        @Test
        @DisplayName("Should not cancel locked task")
        void shouldNotCancelLockedTask() {
            // Given
            testTask.setLockedBy("instance-1");
            testTask.setLockedUntil(Instant.now().plusSeconds(3600));
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));

            // When/Then
            assertThatThrownBy(() -> taskManagementService.cancelTask(testTaskId, "reason"))
                    .isInstanceOf(InvalidTaskStateException.class);
        }

        @Test
        @DisplayName("Should throw TaskNotFoundException when task does not exist")
        void shouldThrowTaskNotFoundExceptionOnCancel() {
            // Given
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> taskManagementService.cancelTask(testTaskId, "reason"))
                    .isInstanceOf(TaskNotFoundException.class);
        }

        @Test
        @DisplayName("Should pause task")
        void shouldPauseTask() {
            // Given
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            taskManagementService.pauseTask(testTaskId);

            // Then
            verify(taskRepository).save(taskCaptor.capture());
            assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatus.PAUSED);
        }

        @Test
        @DisplayName("Should resume paused task")
        void shouldResumePausedTask() {
            // Given
            testTask.setStatus(TaskStatus.PAUSED);
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            taskManagementService.resumeTask(testTaskId);

            // Then
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask savedTask = taskCaptor.getValue();
            assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(savedTask.getScheduledTime()).isNotNull();
        }

        @Test
        @DisplayName("Should schedule retry for failed task")
        void shouldScheduleRetryForFailedTask() {
            // Given
            testTask.setStatus(TaskStatus.FAILED);
            Instant retryTime = Instant.now().plusSeconds(3600);

            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));
            when(taskRepository.save(any(ScheduledTask.class))).thenReturn(testTask);
            when(taskMapper.toResponse(any(ScheduledTask.class))).thenReturn(testTaskResponse);

            // When
            taskManagementService.retryTask(testTaskId, retryTime);

            // Then
            verify(taskRepository).save(taskCaptor.capture());
            ScheduledTask savedTask = taskCaptor.getValue();
            assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.RETRY_PENDING);
            assertThat(savedTask.getScheduledTime()).isEqualTo(retryTime);
        }
    }

    @Nested
    @DisplayName("Task Retrieval Tests")
    class TaskRetrievalTests {

        @Test
        @DisplayName("Should get task by ID")
        void shouldGetTaskById() {
            // Given
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.of(testTask));
            when(taskMapper.toResponse(testTask)).thenReturn(testTaskResponse);

            // When
            Optional<TaskResponse> result = taskManagementService.getTask(testTaskId);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(testTaskId);
        }

        @Test
        @DisplayName("Should return empty for non-existent task")
        void shouldReturnEmptyForNonExistentTask() {
            // Given
            when(taskRepository.findById(testTaskId)).thenReturn(Optional.empty());

            // When
            Optional<TaskResponse> result = taskManagementService.getTask(testTaskId);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
