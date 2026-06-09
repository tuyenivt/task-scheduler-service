package com.example.taskscheduler.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Centralized OpenAPI metadata for the generated /api-docs and Swagger UI.
 * <p>
 * Customizing title, version, contact, and server list (rather than relying on
 * springdoc defaults) makes the rendered docs usable as live documentation -
 * the description spells out the lifecycle and lock model so a reader can
 * understand the API contract without spelunking the source.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:task-scheduler-service}")
    private String applicationName;

    @Bean
    public OpenAPI taskSchedulerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Task Scheduler Service API")
                        .version("v1")
                        .description("""
                                Back-office task scheduler for order cancellations and payment refunds/voids.

                                **Lifecycle:** PENDING/SCHEDULED -> PROCESSING -> COMPLETED | FAILED | RETRY_PENDING | DEAD_LETTER | MAX_RETRIES_EXCEEDED.
                                Tasks are picked up by polling instances using PostgreSQL `FOR UPDATE SKIP LOCKED` and an atomic
                                lock-acquire UPDATE, so the same task is never executed twice concurrently across replicas.

                                **Idempotency:** Bulk cancel is idempotent - re-cancelling an already-CANCELLED task counts as success.
                                Task creation supports `preventDuplicates=true` (default) to reject duplicate active tasks for the
                                same reference + type.

                                **Errors:** 400 for validation, 404 for missing tasks, 409 for duplicate / invalid state transitions,
                                502 for upstream service failures, 500 for unexpected errors. See `ApiResponse.errors[]` for per-field
                                detail on validation failures.

                                **Versioning:** All routes are prefixed `/api/vN/`; today only `v1` exists. Breaking-change
                                criteria, parallel-version policy, and the sunset timeline are documented in
                                [docs/api-versioning.md](https://github.com/example/task-scheduler-service/blob/main/docs/api-versioning.md).
                                """)
                        .contact(new Contact()
                                .name("Platform Team")
                                .email("platform@example.com"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("/").description("Current host"),
                        new Server().url("http://localhost:8080").description("Local development")))
                .components(new Components());
    }
}
