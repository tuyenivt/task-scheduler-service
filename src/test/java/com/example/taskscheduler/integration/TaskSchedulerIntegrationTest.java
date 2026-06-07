package com.example.taskscheduler.integration;

import com.example.taskscheduler.TestcontainersConfiguration;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.dto.CreateTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.web-application-type=servlet",
                "task-scheduler.poll-interval-ms=999999999",
                "slack.enabled=false"
        }
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Task Scheduler Integration Tests")
class TaskSchedulerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduledTaskRepository taskRepository;

    @Autowired
    private TaskExecutionLogRepository executionLogRepository;

    @BeforeEach
    void setUp() {
        executionLogRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Nested
    @DisplayName("Task Creation API")
    class TaskCreationApiTests {

        @Test
        @DisplayName("Should create task via API")
        void shouldCreateTaskViaApi() throws Exception {
            var request = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-INT-001")
                    .description("Integration test order cancellation")
                    .priority(TaskPriority.HIGH)
                    .payload(Map.of("reason", "Test cancellation"))
                    .preventDuplicates(false)
                    .build();

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.taskType").value("ORDER_CANCEL"))
                    .andExpect(jsonPath("$.data.referenceId").value("ORD-INT-001"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.priority").value("HIGH"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty());

            assertThat(taskRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should reject task with missing required fields")
        void shouldRejectTaskWithMissingFields() throws Exception {
            // Send raw JSON with missing taskType to ensure proper validation
            var json = """
                    {"referenceId": "ORD-INT-002"}
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should prevent duplicate tasks")
        void shouldPreventDuplicateTasks() throws Exception {
            var request = CreateTaskRequest.builder()
                    .taskType(TaskType.PAYMENT_REFUND)
                    .referenceId("PAY-DUP-001")
                    .preventDuplicates(true)
                    .build();

            // First creation should succeed
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            // Second creation with same reference should return 409 Conflict
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict());

            // Should still only have 1 task
            assertThat(taskRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should create batch tasks")
        void shouldCreateBatchTasks() throws Exception {
            var requests = java.util.List.of(
                    CreateTaskRequest.builder()
                            .taskType(TaskType.ORDER_CANCEL)
                            .referenceId("ORD-BATCH-001")
                            .preventDuplicates(false)
                            .build(),
                    CreateTaskRequest.builder()
                            .taskType(TaskType.PAYMENT_REFUND)
                            .referenceId("PAY-BATCH-002")
                            .preventDuplicates(false)
                            .build()
            );

            mockMvc.perform(post("/api/v1/tasks/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requests)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.created", hasSize(2)));

            assertThat(taskRepository.count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Task Retrieval API")
    class TaskRetrievalApiTests {

        @Test
        @DisplayName("Should get task by ID")
        void shouldGetTaskById() throws Exception {
            var task = createTestTask("ORD-GET-001", TaskType.ORDER_CANCEL);

            mockMvc.perform(get("/api/v1/tasks/{taskId}", task.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(task.getId().toString()))
                    .andExpect(jsonPath("$.data.referenceId").value("ORD-GET-001"));
        }

        @Test
        @DisplayName("Should return 404 for non-existent task")
        void shouldReturn404ForNonExistentTask() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/{taskId}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should get tasks by reference ID")
        void shouldGetTasksByReferenceId() throws Exception {
            createTestTask("ORD-REF-001", TaskType.ORDER_CANCEL);
            createTestTask("ORD-REF-001", TaskType.PAYMENT_REFUND);

            mockMvc.perform(get("/api/v1/tasks/reference/{referenceId}", "ORD-REF-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(2)));
        }

        @Test
        @DisplayName("Should search tasks with filters")
        void shouldSearchTasksWithFilters() throws Exception {
            createTestTask("ORD-SEARCH-001", TaskType.ORDER_CANCEL);
            createTestTask("PAY-SEARCH-002", TaskType.PAYMENT_REFUND);

            mockMvc.perform(get("/api/v1/tasks")
                            .param("taskType", "ORDER_CANCEL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.content[0].taskType").value("ORDER_CANCEL"));
        }
    }

    @Nested
    @DisplayName("Task Status Management API")
    class TaskStatusManagementApiTests {

        @Test
        @DisplayName("Should cancel a pending task")
        void shouldCancelPendingTask() throws Exception {
            var task = createTestTask("ORD-CANCEL-001", TaskType.ORDER_CANCEL);

            mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", task.getId())
                            .param("reason", "Test cancellation"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));

            var updated = taskRepository.findById(task.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should not cancel completed task")
        void shouldNotCancelCompletedTask() throws Exception {
            var task = createTestTask("ORD-CANCEL-002", TaskType.ORDER_CANCEL);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);

            mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", task.getId()))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Should pause and resume a task")
        void shouldPauseAndResumeTask() throws Exception {
            var task = createTestTask("ORD-PAUSE-001", TaskType.ORDER_CANCEL);

            // Pause
            mockMvc.perform(post("/api/v1/tasks/{taskId}/pause", task.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PAUSED"));

            // Resume
            mockMvc.perform(post("/api/v1/tasks/{taskId}/resume", task.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));

            var updated = taskRepository.findById(task.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(TaskStatus.PENDING);
        }

        @Test
        @DisplayName("Should retry a failed task")
        void shouldRetryFailedTask() throws Exception {
            var task = createTestTask("ORD-RETRY-001", TaskType.ORDER_CANCEL);
            task.setStatus(TaskStatus.FAILED);
            task.setLastError("Connection timeout");
            taskRepository.save(task);

            mockMvc.perform(post("/api/v1/tasks/{taskId}/retry", task.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("RETRY_PENDING"));
        }

        @Test
        @DisplayName("Should bulk cancel tasks")
        void shouldBulkCancelTasks() throws Exception {
            var task1 = createTestTask("ORD-BULK-001", TaskType.ORDER_CANCEL);
            var task2 = createTestTask("ORD-BULK-002", TaskType.ORDER_CANCEL);

            var bulkRequest = Map.of(
                    "taskIds", java.util.List.of(task1.getId().toString(), task2.getId().toString()),
                    "reason", "Bulk cancellation test"
            );

            mockMvc.perform(post("/api/v1/tasks/bulk/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkRequest)))
                    .andExpect(status().isOk())
                    // Bulk cancel now returns BulkCancelResult { succeeded, failed }
                    // instead of an opaque count. Both ids were PENDING so both succeed.
                    .andExpect(jsonPath("$.data.succeeded.length()").value(2))
                    .andExpect(jsonPath("$.data.failed.length()").value(0));
        }
    }

    @Nested
    @DisplayName("Statistics API")
    class StatisticsApiTests {

        @Test
        @DisplayName("Should get task statistics")
        void shouldGetTaskStatistics() throws Exception {
            createTestTask("ORD-STAT-001", TaskType.ORDER_CANCEL);
            createTestTask("PAY-STAT-002", TaskType.PAYMENT_REFUND);

            var task3 = createTestTask("ORD-STAT-003", TaskType.ORDER_CANCEL);
            task3.setStatus(TaskStatus.COMPLETED);
            task3.setCompletedAt(Instant.now());
            taskRepository.save(task3);

            mockMvc.perform(get("/api/v1/tasks/statistics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pendingCount").value(2))
                    .andExpect(jsonPath("$.data.completedCount").value(1));
        }
    }

    @Nested
    @DisplayName("Health Check API")
    class HealthCheckApiTests {

        @Test
        @DisplayName("Should return healthy status")
        void shouldReturnHealthy() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value("OK"));
        }
    }

    @Nested
    @DisplayName("Repository Native Queries")
    class RepositoryNativeQueryTests {

        @Test
        @DisplayName("findTasksForExecution should return executable tasks ordered by priority")
        void shouldFindExecutableTasksOrderedByPriority() {
            var lowPriority = createTestTask("ORD-LOW", TaskType.ORDER_CANCEL);
            lowPriority.setPriority(TaskPriority.LOW);
            taskRepository.save(lowPriority);

            var criticalPriority = createTestTask("ORD-CRIT", TaskType.ORDER_CANCEL);
            criticalPriority.setPriority(TaskPriority.CRITICAL);
            taskRepository.save(criticalPriority);

            var tasks = taskRepository.findTasksForExecution(Instant.now().plusSeconds(1), 10);

            assertThat(tasks).hasSize(2);
            assertThat(tasks.get(0).getPriority()).isEqualTo(TaskPriority.CRITICAL);
            assertThat(tasks.get(1).getPriority()).isEqualTo(TaskPriority.LOW);
        }

        @Test
        @DisplayName("findTasksForExecution should not return future tasks")
        void shouldNotReturnFutureTasks() {
            var futureTask = createTestTask("ORD-FUTURE", TaskType.ORDER_CANCEL);
            futureTask.setScheduledTime(Instant.now().plusSeconds(3600));
            taskRepository.save(futureTask);

            var tasks = taskRepository.findTasksForExecution(Instant.now(), 10);
            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("findTasksForExecution should not return completed tasks")
        void shouldNotReturnCompletedTasks() {
            var completed = createTestTask("ORD-DONE", TaskType.ORDER_CANCEL);
            completed.setStatus(TaskStatus.COMPLETED);
            completed.setCompletedAt(Instant.now());
            taskRepository.save(completed);

            var tasks = taskRepository.findTasksForExecution(Instant.now().plusSeconds(1), 10);
            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("findTasksForExecution should not return expired tasks")
        void shouldNotReturnExpiredTasks() {
            var expired = createTestTask("ORD-EXPIRED", TaskType.ORDER_CANCEL);
            expired.setExpiresAt(Instant.now().minusSeconds(60));
            taskRepository.save(expired);

            var tasks = taskRepository.findTasksForExecution(Instant.now().plusSeconds(1), 10);
            assertThat(tasks).isEmpty();
        }

        @Test
        @DisplayName("existsActiveTaskForReference should find active tasks")
        void shouldFindActiveTaskForReference() {
            createTestTask("ORD-ACTIVE", TaskType.ORDER_CANCEL);

            assertThat(taskRepository.existsActiveTaskForReference("ORD-ACTIVE", TaskType.ORDER_CANCEL)).isTrue();
            assertThat(taskRepository.existsActiveTaskForReference("ORD-NOPE", TaskType.ORDER_CANCEL)).isFalse();
        }

        @Test
        @DisplayName("existsActiveTaskForReference should not count completed as active")
        void shouldNotCountCompletedAsActive() {
            var task = createTestTask("ORD-COMP", TaskType.ORDER_CANCEL);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);

            assertThat(taskRepository.existsActiveTaskForReference("ORD-COMP", TaskType.ORDER_CANCEL)).isFalse();
        }

        @Test
        @DisplayName("Should count tasks by status")
        void shouldCountByStatus() {
            createTestTask("ORD-COUNT-1", TaskType.ORDER_CANCEL);
            createTestTask("ORD-COUNT-2", TaskType.ORDER_CANCEL);

            assertThat(taskRepository.countByStatus(TaskStatus.PENDING)).isEqualTo(2);
            assertThat(taskRepository.countByStatus(TaskStatus.COMPLETED)).isZero();
        }

        @Test
        @DisplayName("Should find stale tasks")
        void shouldFindStaleTasks() {
            var staleTask = createTestTask("ORD-STALE", TaskType.ORDER_CANCEL);
            staleTask.setStatus(TaskStatus.PROCESSING);
            staleTask.setLockedBy("crashed-instance");
            staleTask.setLockedUntil(Instant.now().minusSeconds(7200));
            taskRepository.save(staleTask);

            var staleTasks = taskRepository.findStaleTasks(Instant.now());
            assertThat(staleTasks).hasSize(1);
            assertThat(staleTasks.getFirst().getReferenceId()).isEqualTo("ORD-STALE");
        }

        @Test
        @DisplayName("Should get task stats by status")
        void shouldGetTaskStatsByStatus() {
            createTestTask("ORD-S1", TaskType.ORDER_CANCEL);
            createTestTask("ORD-S2", TaskType.ORDER_CANCEL);

            var stats = taskRepository.getTaskStatsByStatus();
            assertThat(stats).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Flyway Migration Validation")
    class FlywayMigrationTests {

        @Test
        @DisplayName("Database schema should match entity definitions")
        void schemaShouldMatchEntities() {
            // If we get here, Flyway migration ran and Hibernate validate passed
            // This confirms the SQL schema matches the JPA entities
            assertThat(taskRepository.count()).isGreaterThanOrEqualTo(0);
        }
    }

    // Helper method
    private ScheduledTask createTestTask(String referenceId, TaskType taskType) {
        var task = ScheduledTask.builder()
                .taskType(taskType)
                .status(TaskStatus.PENDING)
                .priority(TaskPriority.NORMAL)
                .referenceId(referenceId)
                .scheduledTime(Instant.now().minusSeconds(60))
                .retryCount(0)
                .build();
        return taskRepository.save(task);
    }
}
