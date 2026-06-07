package com.example.taskscheduler.controller;

import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.dto.*;
import com.example.taskscheduler.service.TaskManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API controller for task management operations.
 * <p>
 * Provides endpoints for:
 * - Creating tasks
 * - Retrieving task details and history
 * - Managing task status (cancel, pause, resume, retry)
 * - Searching and filtering tasks
 * - Statistics and health
 */
@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tasks")
@Tag(name = "Task Management", description = "APIs for managing scheduled tasks")
public class TaskController {

    private final TaskManagementService taskManagementService;

    // === Task Creation ===

    @PostMapping
    @Operation(summary = "Create a new task", description = "Create a new scheduled task for execution")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TaskResponse>> createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.info("API: Create task request for type {} reference {}", request.getTaskType(), request.getReferenceId());

        var response = taskManagementService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response, "Task created successfully"));
    }

    @PostMapping("/batch")
    @Operation(summary = "Create multiple tasks", description = "Create multiple tasks in a single request")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<BatchCreateResult>> createTasks(
            @Valid @RequestBody
            @Size(max = 500, message = "Batch must contain at most 500 task requests")
            List<@Valid CreateTaskRequest> requests) {
        log.info("API: Batch create {} tasks", requests.size());

        var result = taskManagementService.createTasks(requests);
        var message = String.format("Created %d tasks", result.getCreated().size());
        if (!result.getErrors().isEmpty()) {
            message += String.format(", %d failed", result.getErrors().size());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result, message));
    }

    // === Task Retrieval ===

    @GetMapping("/{taskId}")
    @Operation(summary = "Get task by ID", description = "Retrieve a task by its unique identifier")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(
            @Parameter(description = "Task UUID") @PathVariable UUID taskId,
            @Parameter(description = "Include execution history")
            @RequestParam(defaultValue = "false") boolean includeHistory) {

        if (includeHistory) {
            return taskManagementService.getTaskWithHistory(taskId)
                    .map(task -> ResponseEntity.ok(ApiResponse.success(task)))
                    .orElse(ResponseEntity.notFound().build());
        }

        return taskManagementService.getTask(taskId)
                .map(task -> ResponseEntity.ok(ApiResponse.success(task)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/reference/{referenceId}")
    @Operation(summary = "Get tasks by reference", description = "Get all tasks for a specific reference ID")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByReference(
            @Parameter(description = "Reference ID (e.g., order ID)") @PathVariable String referenceId) {

        var tasks = taskManagementService.getTasksByReference(referenceId);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping
    @Operation(summary = "Search tasks", description = "Search tasks with optional filters")
    public ResponseEntity<ApiResponse<Page<TaskResponse>>> searchTasks(
            @Parameter(description = "Task type filter") @RequestParam(required = false) TaskType taskType,
            @Parameter(description = "Status filter") @RequestParam(required = false) TaskStatus status,
            @Parameter(description = "Reference ID filter") @RequestParam(required = false) String referenceId,
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0")
            @Max(value = 10_000, message = "page must be <= 10000 (deep pagination is not supported; narrow filters instead)")
            int page,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100")
            int size,
            @Parameter(description = "Sort field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort direction") @RequestParam(defaultValue = "DESC") String sortDir) {

        var criteria = TaskSearchCriteria.builder().taskType(taskType).status(status).referenceId(referenceId).build();
        var sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
        var pageable = PageRequest.of(page, size, sort);

        var tasks = taskManagementService.searchTasks(criteria, pageable);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    // === Task Status Management ===

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "Cancel a task", description = "Cancel a pending or scheduled task")
    public ResponseEntity<ApiResponse<TaskResponse>> cancelTask(
            @Parameter(description = "Task UUID") @PathVariable UUID taskId,
            @Parameter(description = "Cancellation reason") @RequestParam(required = false) String reason) {
        log.info("API: Cancel task {} with reason: {}", taskId, reason);

        var response = taskManagementService.cancelTask(taskId, reason);
        return ResponseEntity.ok(ApiResponse.success(response, "Task cancelled successfully"));
    }

    @PostMapping("/{taskId}/pause")
    @Operation(summary = "Pause a task", description = "Pause a task to prevent execution")
    public ResponseEntity<ApiResponse<TaskResponse>> pauseTask(@Parameter(description = "Task UUID") @PathVariable UUID taskId) {
        log.info("API: Pause task {}", taskId);

        var response = taskManagementService.pauseTask(taskId);
        return ResponseEntity.ok(ApiResponse.success(response, "Task paused successfully"));
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "Resume a paused task", description = "Resume a paused task for execution")
    public ResponseEntity<ApiResponse<TaskResponse>> resumeTask(@Parameter(description = "Task UUID") @PathVariable UUID taskId) {
        log.info("API: Resume task {}", taskId);

        var response = taskManagementService.resumeTask(taskId);
        return ResponseEntity.ok(ApiResponse.success(response, "Task resumed successfully"));
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "Retry a failed task", description = "Schedule a retry for a failed task")
    public ResponseEntity<ApiResponse<TaskResponse>> retryTask(
            @Parameter(description = "Task UUID") @PathVariable UUID taskId,
            @RequestBody(required = false) RetryTaskRequest request) {
        log.info("API: Retry task {}", taskId);

        if (request != null && request.isImmediate()) {
            var future = taskManagementService.retryTaskNow(taskId);
            var response = future.join();
            return ResponseEntity.ok(ApiResponse.success(response, "Task retry executed"));
        } else {
            var scheduledTime = request != null ? request.getScheduledTime() : null;
            var response = taskManagementService.retryTask(taskId, scheduledTime);
            return ResponseEntity.ok(ApiResponse.success(response, "Task retry scheduled"));
        }
    }

    // === Bulk Operations ===

    @PostMapping("/bulk/cancel")
    @Operation(summary = "Cancel multiple tasks", description = "Cancel multiple tasks at once with per-id outcome")
    public ResponseEntity<ApiResponse<BulkCancelResult>> cancelTasks(@Valid @RequestBody BulkTaskRequest request) {
        log.info("API: Bulk cancel {} tasks", request.getTaskIds().size());

        var result = taskManagementService.cancelTasks(request.getTaskIds(), request.getReason());
        var message = String.format("Cancelled %d of %d tasks (%d failed)",
                result.getSucceeded().size(),
                result.getSucceeded().size() + result.getFailed().size(),
                result.getFailed().size());
        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    // === Statistics ===

    @GetMapping("/statistics")
    @Operation(summary = "Get task statistics", description = "Get aggregated task statistics")
    public ResponseEntity<ApiResponse<TaskStatistics>> getStatistics() {
        var stats = taskManagementService.getStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // === Health Check ===

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if the task scheduler is healthy")
    public ResponseEntity<ApiResponse<String>> healthCheck() {
        return ResponseEntity.ok(ApiResponse.success("OK", "Task scheduler is running"));
    }
}
