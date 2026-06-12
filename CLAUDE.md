# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build (uses Gradle wrapper, Gradle 8.14.4)
./gradlew clean build

# Build skipping tests
./gradlew clean build -x test

# Run tests
./gradlew test

# Run unit tests only (excludes integration tests requiring Docker)
./gradlew test --tests "com.example.taskscheduler.service.*" --tests "com.example.taskscheduler.domain.*"

# Run a single test class
./gradlew test --tests "com.example.taskscheduler.service.TaskManagementServiceTest"

# Run a single test method
./gradlew test --tests "com.example.taskscheduler.service.TaskManagementServiceTest.TaskCreationTests.shouldCreateTaskSuccessfully"
```

## Architecture Overview

This is a distributed task scheduler service (Java 21 / Spring Boot 3.5 / PostgreSQL) for back-office operations like order cancellation and payment processing.

### Core Processing Pipeline

**TaskPollingService** (ShedLock-gated) polls on a fixed delay, fetches a batch of ready tasks using `FOR UPDATE SKIP LOCKED`, then dispatches each to **TaskExecutorService** via a virtual thread executor (`Executors.newThreadPerTaskExecutor`). The executor acquires a per-task optimistic lock (version field), resolves the handler from **TaskHandlerRegistry**, and manages the full lifecycle: validation, execution, success/failure/retry handling, metrics, MDC structured logging, and Slack alerting. Graceful shutdown waits up to 30s for in-flight tasks to complete.

### Task Handler Pattern

To add a new task type:
1. Ship a Flyway migration that widens the `chk_task_type` CHECK constraint to permit the new value (expand-then-deploy)
2. Add an enum value to `TaskType`
3. Create a `@Component` implementing `TaskHandler` (must return `getTaskType()` and implement `execute()`)
4. It auto-registers via `TaskHandlerRegistry` (Spring bean discovery at `@PostConstruct`)

The CHECK constraint must permit the new value before any instance running the new code inserts it. See [docs/adding-enum-values.md](docs/adding-enum-values.md) for the full expand-then-deploy recipe (and the riskier removal/contract path).

Existing handlers: `OrderCancelHandler`, `PaymentRefundHandler`, `PaymentVoidHandler` -- each calls an external service via WebClient with Resilience4j circuit breakers. Retry delays use exponential backoff with 10-25% jitter to prevent thundering herd.

### Distributed Locking Strategy

Three layers prevent duplicate processing:
- **ShedLock** on the polling job ensures only one instance polls at a time
- **`FOR UPDATE SKIP LOCKED`** (native PostgreSQL query in `ScheduledTaskRepository.findTasksForExecution`) distributes tasks across instances
- **Optimistic locking** (`@Version` on `ScheduledTask`) prevents race conditions on task updates

A stale task cleanup job (`cleanupStaleTasks`) periodically resets orphaned locks from crashed instances.

### Key Packages

- `controller` - Single REST controller (`/api/v1/tasks`) for CRUD, status management, bulk ops, statistics. Uses `@Validated` for bean validation on request bodies and list items.
- `service` - `TaskManagementService` (API-facing CRUD/lifecycle), `executor/` (polling + execution), `handler/` (per-type business logic), `alert/` (Slack)
- `domain/entity` - `ScheduledTask` (JSONB payload/metadata columns), `TaskExecutionLog`
- `domain/enums` - `TaskStatus` (11 states, `isExecutable()`/`isTerminal()`/`isFailure()` helpers), `TaskType`, `TaskPriority`
- `domain/repository` - JPA repos with native SQL for SKIP LOCKED queries and JSONB filtering
- `config` - Virtual thread executors (`AsyncConfig`), ShedLock, Resilience4j circuit breakers, Micrometer metrics, `SlackProperties` (configurable dashboard URL)
- `mapper` - MapStruct mapper (`TaskMapper`) for entity/DTO conversion
- `client` - WebClient-based clients for Order Service and Payment Service
- `exception` - Domain exceptions (`TaskNotFoundException` -> 404, `DuplicateTaskException` -> 409, `InvalidTaskStateException` -> 409) with `GlobalExceptionHandler`

### Error Handling

Domain exceptions flow through `GlobalExceptionHandler`:
- `TaskNotFoundException` -> 404 Not Found
- `DuplicateTaskException` -> 409 Conflict (when `preventDuplicates=true`)
- `InvalidTaskStateException` -> 409 Conflict (invalid state transitions)
- `ExternalServiceException` -> 502 Bad Gateway (upstream failures never leak to client)
- `ConstraintViolationException` -> 400 Bad Request (bean validation on `@Validated` controller)

The controller does NOT catch exceptions locally -- all exception-to-HTTP mapping is centralized in `GlobalExceptionHandler`.

### Database

- PostgreSQL with Flyway migrations (`src/main/resources/db/migration/`)
- JPA with `ddl-auto: validate` (schema managed by Flyway, not Hibernate)
- JSONB columns for `payload`, `metadata`, and `execution_result` on `ScheduledTask`
- Partial indexes on active task statuses for efficient polling queries
- `shedlock` table for distributed lock coordination
- **Important**: The SQL migration has CHECK constraints restricting `task_type` and `status` values -- adding new enum values requires a new migration (expand-then-deploy recipe in [docs/adding-enum-values.md](docs/adding-enum-values.md))

### Task Status Lifecycle

PENDING/SCHEDULED/FAILED/RETRY_PENDING are executable states. Terminal states: COMPLETED, CANCELLED, EXPIRED, MAX_RETRIES_EXCEEDED, DEAD_LETTER. PAUSED is non-executable but non-terminal (can resume to PENDING).

### Configuration

All configurable via environment variables with defaults in `application.yml`. Key properties live under `task-scheduler.*` prefix (poll interval, batch size, executor pool size, retry defaults, lock durations). External service URLs under `external-services.*`. Resilience4j circuit breakers configured per external service. Slack properties include configurable `dashboard-base-url` for alert links.

### Observability

- MDC structured logging with `taskId`, `taskType`, and `referenceId` context on every task execution
- Log pattern includes MDC fields: `[taskId=%X{taskId} taskType=%X{taskType} ref=%X{referenceId}]`
- Micrometer metrics for task counts, execution time, failures, retries
- Prometheus endpoint at `/actuator/prometheus`

### Testing

Tests use Mockito with `@ExtendWith(MockitoExtension.class)`. Testcontainers dependencies are available for PostgreSQL integration tests (require Docker). Assertions use AssertJ. Handler tests cover execution success/failure paths, validation, retry delay calculations with jitter, and HTTP error code mapping.
