package com.example.taskscheduler.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ErrorCategory Tests")
class ErrorCategoryTest {

    @Test
    @DisplayName("Maps HTTP status codes to 4XX/5XX buckets")
    void mapsHttpStatus() {
        assertThat(ErrorCategory.ofStatus(404)).isEqualTo(ErrorCategory.HTTP_4XX);
        assertThat(ErrorCategory.ofStatus(429)).isEqualTo(ErrorCategory.HTTP_4XX);
        assertThat(ErrorCategory.ofStatus(500)).isEqualTo(ErrorCategory.HTTP_5XX);
        assertThat(ErrorCategory.ofStatus(503)).isEqualTo(ErrorCategory.HTTP_5XX);
        assertThat(ErrorCategory.ofStatus(200)).isEqualTo(ErrorCategory.UNKNOWN);
    }

    @Test
    @DisplayName("CallNotPermittedException wins over everything as CB_OPEN")
    void cbOpenWins() {
        var cb = mock(CallNotPermittedException.class);
        assertThat(ErrorCategory.of(cb)).isEqualTo(ErrorCategory.CB_OPEN);
    }

    @Test
    @DisplayName("TimeoutException maps to TIMEOUT")
    void timeoutMapsToTimeout() {
        assertThat(ErrorCategory.of(new TimeoutException("read"))).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(ErrorCategory.of(ReadTimeoutException.INSTANCE)).isEqualTo(ErrorCategory.TIMEOUT);
    }

    @Test
    @DisplayName("ConnectException and UnknownHostException map to CONNECT_REFUSED")
    void connectIssuesMapToRefused() {
        assertThat(ErrorCategory.of(new ConnectException("nope"))).isEqualTo(ErrorCategory.CONNECT_REFUSED);
        assertThat(ErrorCategory.of(new UnknownHostException("dns"))).isEqualTo(ErrorCategory.CONNECT_REFUSED);
    }

    @Test
    @DisplayName("Walks the cause chain to find a known category")
    void walksCauseChain() {
        var root = new ConnectException("nope");
        var wrapper = new RuntimeException("outer", new RuntimeException("middle", root));
        assertThat(ErrorCategory.of(wrapper)).isEqualTo(ErrorCategory.CONNECT_REFUSED);
    }

    @Test
    @DisplayName("Falls back to UNKNOWN for unclassified throwables")
    void fallsBackToUnknown() {
        assertThat(ErrorCategory.of(new RuntimeException("mystery"))).isEqualTo(ErrorCategory.UNKNOWN);
    }
}
