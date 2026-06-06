package com.example.taskscheduler.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for async processing using Java 21 Virtual Threads.
 * <p>
 * Virtual threads provide:
 * - Lightweight threads (millions possible vs thousands for platform threads)
 * - Automatic blocking operation handling
 * - Better resource utilization for I/O-bound tasks
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${task-scheduler.executor-pool-size:20}")
    private int executorPoolSize;

    @Value("${task-scheduler.dispatch-queue-capacity:200}")
    private int dispatchQueueCapacity;

    /**
     * Virtual Thread executor for task processing.
     * Each task gets its own virtual thread for maximum concurrency.
     */
    @Bean(name = "virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        log.info("Creating Virtual Thread executor for task processing");

        var factory = Thread.ofVirtual().name("task-executor-", 0).factory();

        return Executors.newThreadPerTaskExecutor(factory);
    }

    /**
     * Task executor for Spring's @Async annotation.
     * Uses virtual threads for async method execution.
     */
    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        log.info("Configuring Spring TaskExecutor with virtual threads");

        // ThreadPoolTaskExecutor with virtual threads
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executorPoolSize);
        executor.setMaxPoolSize(executorPoolSize * 2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler((r, e) -> {
            log.warn("Task rejected from async executor, running in caller thread");
            if (!e.isShutdown()) {
                r.run();
            }
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        return executor;
    }

    /**
     * Bounded executor for task dispatch with submit-time backpressure.
     * <p>
     * Backed by virtual threads but capped at {@code executorPoolSize} workers
     * with a bounded queue of {@code dispatchQueueCapacity}. When the queue is
     * full, {@code CallerRunsPolicy} forces the submitter (the polling thread)
     * to run the task itself — this naturally throttles the polling loop until
     * dispatch capacity recovers, preventing unbounded VT accumulation that
     * would otherwise exhaust the Hikari connection pool when downstreams are
     * degraded.
     */
    @Bean(name = "boundedVirtualThreadExecutor")
    public ExecutorService boundedVirtualThreadExecutor() {
        log.info("Creating bounded dispatch executor: {} workers, queue capacity {}, CallerRunsPolicy backpressure",
                executorPoolSize, dispatchQueueCapacity);

        var executor = new ThreadPoolExecutor(
                executorPoolSize,
                executorPoolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(dispatchQueueCapacity),
                Thread.ofVirtual().name("dispatch-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
