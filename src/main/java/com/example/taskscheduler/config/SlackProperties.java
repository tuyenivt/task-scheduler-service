package com.example.taskscheduler.config;

import lombok.Data;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Slack configuration properties.
 * <p>
 * {@link #dashboardBaseUrl} is validated at startup via {@code @URL} so a
 * malformed value fails fast - without the check, the misconfigured base URL
 * would only surface as broken links inside Slack alerts (and "Slack link is
 * broken" is exactly the wrong diagnostic during an incident).
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "slack")
public class SlackProperties {
    private String webhookUrl;
    private String channel = "#oncall-alerts";
    private boolean enabled = true;

    @URL(message = "slack.dashboard-base-url must be a valid http(s) URL")
    private String dashboardBaseUrl = "https://admin.example.com";
}
