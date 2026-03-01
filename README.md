# Task Scheduler Service

A high-volume, distributed task scheduler service for back-office operations built with Java 21, Spring Boot 3, and PostgreSQL.

## Features

- **Virtual Threads (Java 21)**: High-concurrency task processing with minimal resource overhead
- **Distributed Task Locking**: PostgreSQL `FOR UPDATE SKIP LOCKED` prevents duplicate task processing across multiple instances
- **Flexible Task Types**: Extensible handler pattern for different task types (order cancellation, payment refunds, etc.)
- **Configurable Retry Logic**: Per-task retry configuration with exponential backoff and jitter to prevent thundering herd
- **Comprehensive Status Management**: Full task lifecycle (pending, processing, completed, failed, paused, etc.)
- **Domain Exception Handling**: Proper HTTP status codes (404 for not found, 409 for duplicates/invalid state, 502 for upstream failures)
- **Graceful Shutdown**: Waits for in-flight tasks to complete before stopping (30s timeout)
- **Structured Logging**: MDC context with taskId, taskType, and referenceId on every task execution
- **Slack Alerting**: Automatic alerts with configurable dashboard links when tasks exceed max retries
- **Metrics & Observability**: Prometheus metrics, health checks, execution logging
- **Input Validation**: Bean validation on all API inputs including batch operations and bulk requests
- **Custom Metadata Support**: JSONB fields for task-specific data and control parameters
- **API Management**: REST API for task creation, monitoring, and manual intervention

## Architecture

![Architecture](/architecture.png)

## Technology Stack

| Component        | Technology                        |
|------------------|-----------------------------------|
| Language         | Java 21 with Virtual Threads      |
| Framework        | Spring Boot 3.5                   |
| Database         | PostgreSQL with JSONB             |
| Distributed Lock | ShedLock + PostgreSQL SKIP LOCKED |
| HTTP Client      | WebClient (non-blocking)          |
| Circuit Breaker  | Resilience4j                      |
| Metrics          | Micrometer + Prometheus           |
| Documentation    | SpringDoc OpenAPI                 |

## Quick Start

### Prerequisites

- Java 21+
- Gradle 8.14+
- PostgreSQL 18.1+

### Local Development

1. **Build:**
    ```bash
    ./gradlew clean build -DskipTests
    ```

2. **Set up PostgreSQL:**
    ```bash
    # Using Docker
    docker run -d --name taskscheduler-db -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=taskscheduler -p 5432:5432 postgres:18.1-alpine
    ```

3. **Run the application:**
    ```bash
    ./gradlew clean bootRun -Dspring-boot.run.profiles=dev
    ```

4. **Access the API:**
   - Swagger UI: http://localhost:8080/swagger-ui.html
   - Health Check: http://localhost:8080/actuator/health
   - Metrics: http://localhost:8080/actuator/prometheus

## Configuration

### Environment Variables

| Variable                    | Description                 | Default                   |
|-----------------------------|-----------------------------|---------------------------|
| `DB_HOST`                   | PostgreSQL host             | localhost                 |
| `DB_PORT`                   | PostgreSQL port             | 5432                      |
| `DB_NAME`                   | Database name               | taskscheduler             |
| `DB_USERNAME`               | Database user               | postgres                  |
| `DB_PASSWORD`               | Database password           | postgres                  |
| `POLL_INTERVAL_MS`          | Task polling interval (ms)  | 30000                     |
| `TASK_BATCH_SIZE`           | Max tasks per poll          | 100                       |
| `EXECUTOR_POOL_SIZE`        | Concurrent executors        | 20                        |
| `DEFAULT_MAX_RETRIES`       | Default retry limit         | 5                         |
| `DEFAULT_RETRY_DELAY_HOURS` | Default retry delay (hours) | 24                        |
| `SLACK_WEBHOOK_URL`         | Slack webhook for alerts    | -                         |
| `SLACK_CHANNEL`             | Alert channel               | #oncall-alerts            |
| `DASHBOARD_BASE_URL`        | Admin dashboard base URL    | https://admin.example.com |
| `ORDER_SERVICE_URL`         | Order Service base URL      | -                         |
| `PAYMENT_SERVICE_URL`       | Payment Service base URL    | -                         |

## API Usage

### Create a Task

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "ORDER_CANCEL",
    "referenceId": "ORD-12345",
    "description": "Cancel order due to payment failure",
    "priority": "HIGH",
    "payload": {
      "reason": "Payment declined",
      "cancelledBy": "system"
    },
    "metadata": {
      "notifyCustomer": true
    }
  }'
```

### Create a Payment Refund Task

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "PAYMENT_REFUND",
    "referenceId": "PAY-67890",
    "secondaryReferenceId": "TXN-11111",
    "payload": {
      "amount": 99.99,
      "currency": "USD",
      "reason": "Customer requested refund"
    },
    "maxRetries": 3,
    "retryDelayHours": 12
  }'
```

### Query Tasks

```bash
# Get task by ID
curl http://localhost:8080/api/v1/tasks/{taskId}

# Get task with execution history
curl "http://localhost:8080/api/v1/tasks/{taskId}?includeHistory=true"

# Search tasks (supports taskType, status, referenceId filters; page size capped at 100)
curl "http://localhost:8080/api/v1/tasks?taskType=ORDER_CANCEL&status=FAILED&referenceId=ORD-12345&page=0&size=20"

# Get tasks by reference
curl http://localhost:8080/api/v1/tasks/reference/ORD-12345
```

### Manage Task Status

```bash
# Cancel a task
curl -X POST "http://localhost:8080/api/v1/tasks/{taskId}/cancel?reason=Manual%20cancellation"

# Pause a task
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/pause

# Resume a paused task
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/resume

# Retry a failed task immediately
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/retry \
  -H "Content-Type: application/json" \
  -d '{"immediate": true}'

# Schedule retry for specific time
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/retry \
  -H "Content-Type: application/json" \
  -d '{"scheduledTime": "2024-01-15T10:00:00Z"}'
```

### Statistics

```bash
curl http://localhost:8080/api/v1/tasks/statistics
```

## Task Types

### ORDER_CANCEL
Cancels an order in the Order Service.

**Payload:**
- `reason`: Cancellation reason
- `cancelledBy`: Who initiated cancellation

**Metadata:**
- `notifyCustomer`: Send customer notification
- `refundRequired`: Trigger refund

### PAYMENT_REFUND
Processes a payment refund through Payment Service.

**Payload:**
- `amount`: Refund amount (null for full refund)
- `currency`: Currency code
- `reason`: Refund reason
- `requestedBy`: Requestor

### PAYMENT_VOID
Voids a pending payment authorization.

### Adding New Task Types

1. Add enum value to `TaskType`
2. Create handler implementing `TaskHandler`
3. Handler auto-registers via `TaskHandlerRegistry`

```java
@Component
public class MyCustomHandler implements TaskHandler {
    
    @Override
    public TaskType getTaskType() {
        return TaskType.CUSTOM;
    }
    
    @Override
    public TaskExecutionResult execute(ScheduledTask task) {
        // Your logic here
        return TaskExecutionResult.success();
    }
}
```

## Error Responses

| HTTP Status | When                                                         |
|-------------|--------------------------------------------------------------|
| 400         | Validation errors, invalid request parameters                |
| 404         | Task not found                                               |
| 409         | Duplicate task (preventDuplicates), invalid state transition |
| 502         | External service (Order/Payment) failure                     |
| 500         | Unexpected server error                                      |

## Distributed Processing

### How Multiple Instances Work

1. **ShedLock** ensures only one instance runs the polling job
2. **`FOR UPDATE SKIP LOCKED`** allows concurrent task fetching without conflicts
3. **Optimistic locking** (version field) prevents race conditions on updates
4. **Task locks** with expiration handle instance failures

### Scaling Recommendations

- **Minimum**: 3 replicas for high availability
- **Maximum**: Limited by database connection pool and task volume
- **Database Pool**: `EXECUTOR_POOL_SIZE * number_of_replicas + 10`

## Monitoring

### Prometheus Metrics

| Metric                                | Description                  |
|---------------------------------------|------------------------------|
| `task_scheduler_tasks`                | Task count by status         |
| `task_scheduler_tasks_by_type`        | Task count by type           |
| `task_scheduler_queue_depth`          | Pending executable tasks     |
| `task_scheduler_execution_time`       | Task execution duration      |
| `task_scheduler_failures`             | Failed task counter          |
| `task_scheduler_retries`              | Retry counter                |
| `task_scheduler_max_retries_exceeded` | Max retries exceeded counter |

### Health Endpoints

- `/actuator/health` - Overall health
- `/actuator/health/liveness` - Liveness probe
- `/actuator/health/readiness` - Readiness probe
- `/actuator/prometheus` - Prometheus metrics

## Troubleshooting

### Stale Tasks

Tasks can become stale if:
- Instance crashes during processing
- Task execution times out
- Network issues occur

The `cleanupStaleTasks` job automatically resets them.

### High Queue Depth

1. Increase `EXECUTOR_POOL_SIZE`
2. Scale up replicas
3. Check external service health
4. Review task batch size

### Slack Alerts Not Working

1. Verify `SLACK_WEBHOOK_URL` is set
2. Check network egress policies
3. Review application logs
