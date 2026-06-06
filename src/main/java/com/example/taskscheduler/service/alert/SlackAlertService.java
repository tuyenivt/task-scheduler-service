package com.example.taskscheduler.service.alert;

import com.example.taskscheduler.config.SlackProperties;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskPriority;
import com.slack.api.Slack;
import com.slack.api.model.Attachment;
import com.slack.api.model.Field;
import com.slack.api.webhook.Payload;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Service for sending alerts to Slack when tasks reach max retries.
 * <p>
 * Wrapped with a Resilience4j circuit breaker and retry so a degraded Slack
 * webhook does not pin async worker threads, and so dropped alerts are
 * observable via {@code task_scheduler_alerts_dropped_total} - the metric
 * Prometheus alerting watches to catch alert-pipeline failures (a silently
 * failing alert pipeline is precisely the worst-case for an oncall flow).
 */
@Slf4j
@Service
public class SlackAlertService {

    private static final String CB_NAME = "slackAlerts";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final SlackProperties slackProperties;
    private final MeterRegistry meterRegistry;
    private final SlackAlertService self;
    private final Slack slack = Slack.getInstance();

    @Value("${spring.application.name:task-scheduler}")
    private String applicationName;

    public SlackAlertService(SlackProperties slackProperties,
                             MeterRegistry meterRegistry,
                             @Lazy SlackAlertService self) {
        this.slackProperties = slackProperties;
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    /**
     * Send alert for max retries exceeded.
     */
    @Async
    public void sendMaxRetriesExceededAlert(ScheduledTask task) {
        if (slackDisabled("Task " + task.getId() + " reached max retries but Slack is disabled")) {
            return;
        }
        self.deliverPayload(buildMaxRetriesPayload(task), "max_retries_exceeded");
    }

    /**
     * Send generic error alert.
     */
    @Async
    public void sendErrorAlert(String title, String message, String details) {
        if (slackDisabled("Error alert dropped (Slack disabled): " + title)) {
            return;
        }
        self.deliverPayload(buildErrorPayload(title, message, details), "error");
    }

    /**
     * Send task failure alert for critical-priority tasks.
     */
    @Async
    public void sendTaskFailureAlert(ScheduledTask task, String errorMessage) {
        if (!slackProperties.isEnabled() || isWebhookBlank()) {
            return;
        }
        if (task.getPriority() == null || task.getPriority().getValue() < TaskPriority.HIGH.getValue()) {
            return;
        }
        self.deliverPayload(buildTaskFailurePayload(task, errorMessage), "task_failure");
    }

    /**
     * Deliver a pre-built payload through the circuit breaker and retry.
     * <p>
     * Public + proxy-mediated via {@code self} so the Resilience4j advice
     * actually applies; a direct {@code this.deliverPayload(...)} call would
     * bypass the AOP proxy.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deliverFallback")
    @Retry(name = CB_NAME)
    public void deliverPayload(Payload payload, String alertKind) {
        try {
            var response = slack.send(slackProperties.getWebhookUrl(), payload);
            if (response.getCode() != 200) {
                // Non-2xx is a delivery failure - throw so retry/circuit-breaker count it.
                throw new SlackDeliveryException(
                        "Slack webhook returned " + response.getCode() + ": " + response.getBody());
            }
            log.info("Slack alert delivered ({})", alertKind);
        } catch (SlackDeliveryException e) {
            throw e;
        } catch (Exception e) {
            // Wrap to a runtime exception so Resilience4j observes it.
            throw new SlackDeliveryException("Slack webhook call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback invoked when the circuit is open or retries are exhausted.
     * Signature must match {@link #deliverPayload} plus the trailing Throwable.
     */
    @SuppressWarnings("unused") // referenced by name from @CircuitBreaker
    public void deliverFallback(Payload payload, String alertKind, Throwable cause) {
        log.error("Slack alert DROPPED ({}): {}", alertKind, cause.getMessage());
        meterRegistry.counter("task_scheduler_alerts_dropped_total",
                "kind", alertKind != null ? alertKind : "unknown"
        ).increment();
    }

    // --- payload builders ---

    private Payload buildErrorPayload(String title, String message, String details) {
        return Payload.builder()
                .channel(slackProperties.getChannel())
                .username(applicationName)
                .iconEmoji(":warning:")
                .text(":warning: *" + title + "*")
                .attachments(List.of(
                        Attachment.builder()
                                .color("warning")
                                .text(message)
                                .fields(details != null ? List.of(
                                        Field.builder()
                                                .title("Details")
                                                .value(truncate(details, 500))
                                                .valueShortEnough(false)
                                                .build()
                                ) : List.of())
                                .footer(applicationName)
                                .ts(String.valueOf(Instant.now().getEpochSecond()))
                                .build()
                ))
                .build();
    }

    private Payload buildTaskFailurePayload(ScheduledTask task, String errorMessage) {
        return Payload.builder()
                .channel(slackProperties.getChannel())
                .username(applicationName)
                .iconEmoji(":rotating_light:")
                .text(":rotating_light: *Critical Task Failed*")
                .attachments(List.of(
                        Attachment.builder()
                                .color("danger")
                                .title("Task: " + task.getTaskType().getDisplayName())
                                .titleLink(buildTaskLink(task.getId().toString()))
                                .fields(Arrays.asList(
                                        Field.builder()
                                                .title("Task ID")
                                                .value(task.getId().toString())
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Reference")
                                                .value(task.getReferenceId())
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Error")
                                                .value(truncate(errorMessage, 300))
                                                .valueShortEnough(false)
                                                .build()
                                ))
                                .footer(applicationName)
                                .ts(String.valueOf(Instant.now().getEpochSecond()))
                                .build()
                ))
                .build();
    }

    private Payload buildMaxRetriesPayload(ScheduledTask task) {
        var taskId = task.getId().toString();
        var taskType = task.getTaskType().getDisplayName();
        var referenceId = task.getReferenceId();
        var lastError = task.getLastError() != null ? task.getLastError() : "Unknown error";

        return Payload.builder()
                .channel(slackProperties.getChannel())
                .username(applicationName)
                .iconEmoji(":rotating_light:")
                .text(":rotating_light: *Task Max Retries Exceeded - Manual Intervention Required*")
                .attachments(List.of(
                        Attachment.builder()
                                .color("danger")
                                .title(taskType + " - " + referenceId)
                                .titleLink(buildTaskLink(taskId))
                                .fields(Arrays.asList(
                                        Field.builder()
                                                .title("Task ID")
                                                .value(taskId)
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Task Type")
                                                .value(taskType)
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Reference ID")
                                                .value(referenceId)
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Retry Count")
                                                .value(String.valueOf(task.getRetryCount()))
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Created At")
                                                .value(DATE_FORMATTER.format(task.getCreatedAt()))
                                                .valueShortEnough(true)
                                                .build(),
                                        Field.builder()
                                                .title("Last Error")
                                                .value("```" + truncate(lastError, 400) + "```")
                                                .valueShortEnough(false)
                                                .build()
                                ))
                                .footer(applicationName + " | Please investigate and manually retry or cancel")
                                .ts(String.valueOf(Instant.now().getEpochSecond()))
                                .build()
                ))
                .build();
    }

    private boolean slackDisabled(String reasonLog) {
        if (!slackProperties.isEnabled() || isWebhookBlank()) {
            log.warn(reasonLog);
            return true;
        }
        return false;
    }

    private boolean isWebhookBlank() {
        return slackProperties.getWebhookUrl() == null || slackProperties.getWebhookUrl().isBlank();
    }

    private String buildTaskLink(String taskId) {
        return slackProperties.getDashboardBaseUrl() + "/tasks/" + taskId;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /** Raised when a Slack delivery attempt fails so Resilience4j observes it. */
    static class SlackDeliveryException extends RuntimeException {
        SlackDeliveryException(String message) {
            super(message);
        }
        SlackDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
