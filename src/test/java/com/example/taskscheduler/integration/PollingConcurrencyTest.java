package com.example.taskscheduler.integration;

import com.example.taskscheduler.TestcontainersConfiguration;
import com.example.taskscheduler.domain.entity.ScheduledTask;
import com.example.taskscheduler.domain.enums.TaskStatus;
import com.example.taskscheduler.domain.enums.TaskType;
import com.example.taskscheduler.domain.repository.ScheduledTaskRepository;
import com.example.taskscheduler.domain.repository.TaskExecutionLogRepository;
import com.example.taskscheduler.service.executor.TaskPollingService;
import com.example.taskscheduler.service.handler.TaskExecutionResult;
import com.example.taskscheduler.service.handler.TaskHandler;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the executor pipeline's core invariant: with N tasks and M concurrent
 * dispatch threads all racing to process every task, each task is executed
 * <strong>exactly once</strong>.
 * <p>
 * This is the architecture's reason for existing - if it can be broken by
 * concurrent dispatch, the whole lock + atomic-acquire-UPDATE + version model
 * is wrong. The {@code @Scheduled} fire path is bypassed (it is ShedLock-gated
 * for cluster-wide single-runner semantics, so it would mask the in-process
 * race). Instead, M threads each fire {@link TaskPollingService#processTaskAsync(UUID)}
 * for every task id - the maximum pressure scenario for the lock-acquire UPDATE.
 */
@SpringBootTest(properties = {
        // Disable the automatic poller; we drive it manually.
        "task-scheduler.poll-interval-ms=999999999",
        // Disable the stale-task cleanup so it does not interfere with the run.
        "task-scheduler.stale-task-check-interval-ms=999999999",
        "slack.enabled=false",
        // Tighten timers so the test is fast.
        "task-scheduler.lock-duration=2m",
        "task-scheduler.batch-size=20"
})
@Import({TestcontainersConfiguration.class, PollingConcurrencyTest.TestHandlerConfig.class})
@DisplayName("Polling Pipeline Concurrency Test")
class PollingConcurrencyTest {

    private static final int TASK_COUNT = 50;
    private static final int DISPATCH_THREADS = 8;

    @Autowired
    private TaskPollingService taskPollingService;

    @Autowired
    private ScheduledTaskRepository taskRepository;

    @Autowired
    private TaskExecutionLogRepository executionLogRepository;

    @Autowired
    private CountingHandler countingHandler;

    @BeforeEach
    void resetState() {
        executionLogRepository.deleteAll();
        taskRepository.deleteAll();
        countingHandler.reset();
    }

    @Test
    @DisplayName("Each task executes exactly once when M threads race to dispatch every task")
    void eachTaskExecutesExactlyOnce() throws Exception {
        // Seed N PENDING tasks of type CUSTOM (no production handler claims it).
        var now = Instant.now();
        var taskIds = new ArrayList<UUID>(TASK_COUNT);
        for (int i = 0; i < TASK_COUNT; i++) {
            var saved = taskRepository.save(ScheduledTask.builder()
                    .taskType(TaskType.CUSTOM)
                    .status(TaskStatus.PENDING)
                    .priority(com.example.taskscheduler.domain.enums.TaskPriority.NORMAL)
                    .referenceId("CONCURRENCY-" + i)
                    .scheduledTime(now.minusSeconds(1))
                    .retryCount(0)
                    .build());
            taskIds.add(saved.getId());
        }

        // Launch M threads. Each thread iterates every task id and submits a
        // dispatch attempt - so every (thread, task) pair races for the lock.
        // The atomic acquire-UPDATE inside acquireLockAndFetch is the only
        // coordination point; exactly one thread per task should win and run
        // the handler.
        var startGate = new CountDownLatch(1);
        var done = new CountDownLatch(DISPATCH_THREADS);
        var dispatcherExec = Executors.newFixedThreadPool(DISPATCH_THREADS);
        var hadException = new AtomicBoolean(false);

        for (int t = 0; t < DISPATCH_THREADS; t++) {
            dispatcherExec.submit(() -> {
                try {
                    startGate.await();
                    // Fire-and-wait for every task id; processTaskAsync returns a
                    // future for the dispatch attempt itself (not the handler),
                    // so joining here ensures the lock-acquire UPDATE has run.
                    for (var id : taskIds) {
                        try {
                            taskPollingService.processTaskAsync(id).join();
                        } catch (Exception e) {
                            // A "task not found" race is acceptable - just means
                            // another thread already completed it. Anything else
                            // is a real failure.
                            if (!(e.getCause() instanceof IllegalArgumentException)) {
                                hadException.set(true);
                                throw e;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        dispatcherExec.shutdown();
        assertThat(finished).as("dispatchers finished within timeout").isTrue();
        assertThat(hadException).as("no dispatcher threw an unexpected exception").isFalse();

        // Wait for the system to quiesce: every CUSTOM task should reach COMPLETED.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    long completed = taskRepository.findAll().stream()
                            .filter(t -> t.getTaskType() == TaskType.CUSTOM
                                    && t.getStatus() == TaskStatus.COMPLETED)
                            .count();
                    assertThat(completed).isEqualTo(TASK_COUNT);
                });

        // === Core invariant: exactly-once execution ===
        // Total handler invocations across all threads must equal TASK_COUNT.
        // Any value > TASK_COUNT means the lock model failed and the same task
        // was executed concurrently or sequentially by two dispatchers.
        assertThat(countingHandler.totalInvocations())
                .as("total handler invocations across all dispatchers")
                .isEqualTo(TASK_COUNT);

        // Per-task invocation count must be exactly 1 - belt-and-braces over
        // the total assertion above (catches the case where one task was double-
        // executed and another was missed, which would still sum to TASK_COUNT).
        var perTaskCounts = countingHandler.perTaskCounts();
        assertThat(perTaskCounts).hasSize(TASK_COUNT);
        assertThat(perTaskCounts.values())
                .as("each task was handled exactly once")
                .allMatch(c -> c == 1);

        // No task should have a retry_count above 0 - the handler returns
        // success, so any retry would mean the executor saw a lock conflict
        // or transient DB error and re-tried, which is itself a smell here.
        List<ScheduledTask> all = taskRepository.findAll();
        assertThat(all.stream().filter(t -> t.getRetryCount() > 0))
                .as("no task was retried")
                .isEmpty();
    }

    // === Test plumbing ===

    /**
     * Handler that records every {@link #execute} call so the test can assert
     * exactly-once dispatch. Thread-safe: pollers run concurrently, so the
     * counters must tolerate concurrent increment without losing updates.
     */
    static class CountingHandler implements TaskHandler {

        private final AtomicInteger total = new AtomicInteger();
        private final ConcurrentHashMap<String, AtomicInteger> perTask = new ConcurrentHashMap<>();

        @Override
        public TaskType getTaskType() {
            return TaskType.CUSTOM;
        }

        @Override
        public TaskExecutionResult execute(ScheduledTask task) {
            total.incrementAndGet();
            perTask.computeIfAbsent(task.getReferenceId(), k -> new AtomicInteger()).incrementAndGet();
            return TaskExecutionResult.success(Map.of("ref", task.getReferenceId()));
        }

        int totalInvocations() {
            return total.get();
        }

        Map<String, Integer> perTaskCounts() {
            var snapshot = new java.util.HashMap<String, Integer>();
            perTask.forEach((k, v) -> snapshot.put(k, v.get()));
            return snapshot;
        }

        void reset() {
            total.set(0);
            perTask.clear();
        }
    }

    @TestConfiguration
    static class TestHandlerConfig {
        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }
}
