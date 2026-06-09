package com.example.taskscheduler.service.handler;

import com.example.taskscheduler.config.TaskSchedulerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared jitter helper for handler retry-delay computations.
 * <p>
 * Each handler decides its own base delay (exponential, conservative, daily,
 * etc.). Jitter is a single horizontal concern: every handler adds a random
 * {@code [floor%, ceiling%]} kick on top of its base so retries do not cluster
 * after a downstream outage. Centralizing the math means operators tune one
 * knob pair under {@code task-scheduler.retry.*} and every handler picks up
 * the new spread on the next call.
 */
@Component
@RequiredArgsConstructor
public class RetryDelayCalculator {

    private final TaskSchedulerProperties properties;

    /**
     * Add the configured jitter band to {@code baseDelayMs}.
     * <p>
     * Returns the base unchanged when the base is too small for the percent
     * math to produce a non-zero floor (a few milliseconds) - avoids the
     * {@code ThreadLocalRandom.nextLong(low, high)} contract violation when
     * {@code low >= high}.
     */
    public long addJitter(long baseDelayMs) {
        if (baseDelayMs <= 0) {
            return baseDelayMs;
        }
        long floor = baseDelayMs * properties.getRetry().getJitterFloorPercent() / 100L;
        long ceiling = baseDelayMs * properties.getRetry().getJitterCeilingPercent() / 100L + 1L;
        if (floor >= ceiling) {
            return baseDelayMs;
        }
        long jitter = ThreadLocalRandom.current().nextLong(floor, ceiling);
        return baseDelayMs + jitter;
    }
}
