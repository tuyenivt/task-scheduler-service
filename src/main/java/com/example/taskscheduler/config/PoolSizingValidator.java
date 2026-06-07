package com.example.taskscheduler.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Startup guardrail comparing the Hikari connection pool size against the
 * task-scheduler executor pool size.
 * <p>
 * Each in-flight task may hold up to one DB connection at a time (acquire-lock,
 * execute, save). If the pool is sized smaller than the executor's worker
 * count, the executor will routinely queue waiting on connections and the
 * service will exhibit head-of-line blocking under load. The relationship
 * cannot be exactly enforced from inside one instance (the cluster-level
 * formula is {@code hikari >= executor_pool_size * replicas + headroom}), so
 * this check is a loud per-instance warning rather than a fail-fast.
 */
@Slf4j
@Component
public class PoolSizingValidator {

    /** Minimum extra connections beyond the executor pool, for Flyway, actuator, etc. */
    private static final int MIN_HEADROOM = 5;

    private final DataSource dataSource;
    private final TaskSchedulerProperties properties;

    public PoolSizingValidator(DataSource dataSource, TaskSchedulerProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkPoolSizing() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            log.debug("DataSource is not HikariDataSource ({}); skipping pool-sizing check",
                    dataSource.getClass().getName());
            return;
        }

        int hikariMax = hikari.getMaximumPoolSize();
        int executorPool = properties.getExecutorPoolSize();
        int minRequired = executorPool + MIN_HEADROOM;

        if (hikariMax < minRequired) {
            log.warn(
                    "Hikari maximum-pool-size={} is below the recommended minimum of {} "
                            + "(executor-pool-size={} + headroom={}). Under load, executor workers "
                            + "will block waiting on DB connections. Consider raising "
                            + "spring.datasource.hikari.maximum-pool-size to at least {}.",
                    hikariMax, minRequired, executorPool, MIN_HEADROOM, minRequired);
        } else {
            log.info("Pool sizing OK: Hikari max={} >= executor pool={} + headroom={}",
                    hikariMax, executorPool, MIN_HEADROOM);
        }
    }
}
