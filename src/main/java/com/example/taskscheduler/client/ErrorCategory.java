package com.example.taskscheduler.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Stable, low-cardinality categories for external-service failures.
 * <p>
 * Used as the {@code error_type} tag on {@code task_scheduler_failures} so
 * alerting can route distinct symptoms - a timeout storm needs a different
 * runbook than an open circuit breaker or a 4xx wave from a contract change.
 */
public enum ErrorCategory {
    /** Read/write/response timeout (Netty or Reactor timeout()). */
    TIMEOUT,
    /** TCP connect failed - service unreachable or DNS resolution failure. */
    CONNECT_REFUSED,
    /** Resilience4j circuit breaker is open; the call never reached the network. */
    CB_OPEN,
    /** Downstream returned 4xx - usually a contract / validation failure. */
    HTTP_4XX,
    /** Downstream returned 5xx - upstream is degraded. */
    HTTP_5XX,
    /** Any other failure we have not classified explicitly. */
    UNKNOWN;

    /**
     * Classify any throwable into one of the categories above by walking the
     * cause chain. Resilience4j's {@code CallNotPermittedException} wins over
     * HTTP status because it means the call never happened.
     */
    public static ErrorCategory of(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof CallNotPermittedException) {
                return CB_OPEN;
            }
            if (cur instanceof TimeoutException
                    || cur instanceof ReadTimeoutException
                    || cur instanceof WriteTimeoutException) {
                return TIMEOUT;
            }
            if (cur instanceof ConnectException || cur instanceof UnknownHostException) {
                return CONNECT_REFUSED;
            }
            // Reactor wraps a connect failure in WebClientRequestException; the
            // underlying cause is the meaningful one and is checked above on
            // the next iteration of the loop.
            if (cur instanceof WebClientRequestException && cur.getCause() == null) {
                return CONNECT_REFUSED;
            }
        }
        return UNKNOWN;
    }

    /**
     * Classify a known HTTP status. Use this in branches that have parsed the
     * status code (it is more authoritative than walking the cause chain).
     */
    public static ErrorCategory ofStatus(int statusCode) {
        if (statusCode >= 500) {
            return HTTP_5XX;
        }
        if (statusCode >= 400) {
            return HTTP_4XX;
        }
        return UNKNOWN;
    }
}
