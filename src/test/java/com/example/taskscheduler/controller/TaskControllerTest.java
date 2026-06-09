package com.example.taskscheduler.controller;

import com.example.taskscheduler.domain.enums.TaskPriority;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.dto.CreateTaskRequest;
import com.example.taskscheduler.dto.TaskResponse;
import com.example.taskscheduler.exception.DuplicateTaskException;
import com.example.taskscheduler.exception.ExternalServiceException;
import com.example.taskscheduler.exception.InvalidTaskStateException;
import com.example.taskscheduler.exception.TaskNotFoundException;
import com.example.taskscheduler.service.TaskManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for {@link TaskController} that pins the contract
 * {@code GlobalExceptionHandler} enforces - every domain exception must map to
 * the documented HTTP status with the {@code ApiResponse} error envelope shape.
 * <p>
 * Catching a regression here is far cheaper than catching it after a deploy
 * with a client noticing 500s where 404s used to be.
 */
@WebMvcTest(TaskController.class)
@DisplayName("TaskController @WebMvcTest")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskManagementService taskManagementService;

    private static final String BASE = "/api/v1/tasks";

    @Nested
    @DisplayName("Happy path")
    class HappyPathTests {

        @Test
        @DisplayName("POST / returns 201 with ApiResponse envelope")
        void createTaskReturns201() throws Exception {
            var body = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-OK")
                    .preventDuplicates(false)
                    .build();
            var taskId = UUID.randomUUID();
            when(taskManagementService.createTask(any())).thenReturn(
                    TaskResponse.builder()
                            .id(taskId)
                            .taskType(TaskType.ORDER_CANCEL)
                            .status(TaskStatus.PENDING)
                            .priority(TaskPriority.NORMAL)
                            .referenceId("ORD-OK")
                            .build());

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(taskId.toString()));
        }
    }

    @Nested
    @DisplayName("Exception -> HTTP status contract")
    class ExceptionMappingTests {

        @Test
        @DisplayName("Missing required field -> 400 via @Valid")
        void missingReferenceIdReturns400() throws Exception {
            var body = Map.of("taskType", "ORDER_CANCEL"); // referenceId omitted

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors").isArray());
        }

        @Test
        @DisplayName("TaskNotFoundException -> 404")
        void taskNotFoundReturns404() throws Exception {
            var taskId = UUID.randomUUID();
            when(taskManagementService.getTask(eq(taskId)))
                    .thenThrow(new TaskNotFoundException(taskId));

            mockMvc.perform(get(BASE + "/" + taskId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Task not found")));
        }

        @Test
        @DisplayName("DuplicateTaskException -> 409")
        void duplicateTaskReturns409() throws Exception {
            var body = CreateTaskRequest.builder()
                    .taskType(TaskType.ORDER_CANCEL)
                    .referenceId("ORD-DUP")
                    .build();
            when(taskManagementService.createTask(any()))
                    .thenThrow(new DuplicateTaskException("ORD-DUP", "ORDER_CANCEL"));

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Active task already exists")));
        }

        @Test
        @DisplayName("InvalidTaskStateException -> 409")
        void invalidTaskStateReturns409() throws Exception {
            var taskId = UUID.randomUUID();
            when(taskManagementService.cancelTask(eq(taskId), any()))
                    .thenThrow(new InvalidTaskStateException(
                            taskId.toString(), "COMPLETED", "CANCELLED"));

            mockMvc.perform(post(BASE + "/" + taskId + "/cancel"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Cannot transition")));
        }

        @Test
        @DisplayName("ExternalServiceException -> 502")
        void externalServiceFailureReturns502() throws Exception {
            var taskId = UUID.randomUUID();
            when(taskManagementService.getTask(eq(taskId)))
                    .thenThrow(new ExternalServiceException("Order Service", "downstream blew up"));

            mockMvc.perform(get(BASE + "/" + taskId))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("External service error")));
        }

        @Test
        @DisplayName("Pagination over the cap -> 400 via HandlerMethodValidationException")
        void oversizedPageSizeReturns400() throws Exception {
            // size=500 violates @Max(100) on the search endpoint.
            mockMvc.perform(get(BASE + "?size=500"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors").isArray());
        }
    }
}
