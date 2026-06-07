package com.example.taskscheduler.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PoolSizingValidator Tests")
class PoolSizingValidatorTest {

    @Test
    @DisplayName("Should run without exception when Hikari pool >= executor + headroom")
    void shouldAcceptAdequatePool() {
        var hikari = mock(HikariDataSource.class);
        when(hikari.getMaximumPoolSize()).thenReturn(30);

        var props = new TaskSchedulerProperties();
        props.setExecutorPoolSize(20); // 20 + 5 headroom = 25 <= 30

        new PoolSizingValidator(hikari, props).checkPoolSizing();
        // Asserted by absence of thrown exceptions; the WARN/INFO log is
        // observable in the build log but not asserted here.
    }

    @Test
    @DisplayName("Should run without exception when Hikari pool is undersized (logs WARN)")
    void shouldStillRunWhenUndersized() {
        var hikari = mock(HikariDataSource.class);
        when(hikari.getMaximumPoolSize()).thenReturn(10);

        var props = new TaskSchedulerProperties();
        props.setExecutorPoolSize(20); // 20 + 5 headroom = 25 > 10 -> WARN

        new PoolSizingValidator(hikari, props).checkPoolSizing();
        // The validator does not fail-fast; replica-aware sizing is operator
        // responsibility, so this just confirms the check itself does not throw.
    }

    @Test
    @DisplayName("Should skip silently for non-Hikari DataSource")
    void shouldSkipForNonHikariDataSource() {
        var ds = mock(DataSource.class);
        var props = new TaskSchedulerProperties();
        props.setExecutorPoolSize(20);

        new PoolSizingValidator(ds, props).checkPoolSizing();
        // No interaction with the mock beyond getClass(); test passes if no NPE.
    }
}
