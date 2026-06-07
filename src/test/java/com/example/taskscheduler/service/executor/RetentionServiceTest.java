package com.example.taskscheduler.service.executor;

import com.example.taskscheduler.config.TaskSchedulerProperties;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionService Tests")
class RetentionServiceTest {

    @Mock
    private ScheduledTaskRepository taskRepository;

    @Mock
    private TaskExecutionLogRepository executionLogRepository;

    private TaskSchedulerProperties properties;

    @InjectMocks
    private RetentionService retentionService;

    @BeforeEach
    void setUp() {
        properties = new TaskSchedulerProperties();
        properties.setRetentionDays(30);
        properties.setExecutionLogRetentionDays(30);
        retentionService = new RetentionService(taskRepository, executionLogRepository, properties);
    }

    @Test
    @DisplayName("Should delete terminal tasks and stale logs using configured cutoffs")
    void shouldDeleteWithConfiguredCutoffs() {
        when(taskRepository.deleteOldCompletedTasks(any(Instant.class))).thenReturn(12);
        when(executionLogRepository.deleteOldLogs(any(Instant.class))).thenReturn(34);

        var before = Instant.now();
        retentionService.purgeOldRows();
        var after = Instant.now();

        var taskCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).deleteOldCompletedTasks(taskCaptor.capture());
        // Cutoff should be ~30 days before "now"; allow a small wall-clock window.
        var expectedLow = before.minus(30, ChronoUnit.DAYS);
        var expectedHigh = after.minus(30, ChronoUnit.DAYS);
        assertThat(taskCaptor.getValue()).isBetween(expectedLow, expectedHigh);

        var logCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(executionLogRepository).deleteOldLogs(logCaptor.capture());
        assertThat(logCaptor.getValue()).isBetween(expectedLow, expectedHigh);
    }

    @Test
    @DisplayName("Should floor log retention to task retention so logs do not outlive parents")
    void shouldFloorLogRetentionToTaskRetention() {
        properties.setRetentionDays(30);
        properties.setExecutionLogRetentionDays(7); // misconfigured: shorter than task retention

        retentionService.purgeOldRows();

        var logCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(executionLogRepository).deleteOldLogs(logCaptor.capture());

        var taskCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskRepository).deleteOldCompletedTasks(taskCaptor.capture());

        // Log cutoff must equal task cutoff (older window), not the 7-day one.
        assertThat(logCaptor.getValue()).isEqualTo(taskCaptor.getValue());
    }
}
