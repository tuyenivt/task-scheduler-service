package com.example.taskscheduler.service.alert;

import com.example.taskscheduler.config.SlackProperties;
import com.slack.api.webhook.Payload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SlackAlertService Tests")
class SlackAlertServiceTest {

    @Test
    @DisplayName("Fallback increments dropped-alert counter tagged with alert kind")
    void fallbackIncrementsDroppedCounter() {
        var registry = new SimpleMeterRegistry();
        var service = new SlackAlertService(new SlackProperties(), registry, null);
        var payload = Payload.builder().text("ignored").build();

        service.deliverFallback(payload, "max_retries_exceeded",
                new SlackAlertService.SlackDeliveryException("simulated outage"));

        var counter = registry.find("task_scheduler_alerts_dropped_total")
                .tag("kind", "max_retries_exceeded")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fallback tags unknown kind when alertKind is null")
    void fallbackTagsUnknownWhenKindNull() {
        var registry = new SimpleMeterRegistry();
        var service = new SlackAlertService(new SlackProperties(), registry, null);

        service.deliverFallback(Payload.builder().build(), null,
                new RuntimeException("boom"));

        var counter = registry.find("task_scheduler_alerts_dropped_total")
                .tag("kind", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
