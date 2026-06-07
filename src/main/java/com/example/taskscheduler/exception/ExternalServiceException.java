package com.example.taskscheduler.exception;

import com.example.taskscheduler.client.ErrorCategory;
import lombok.Getter;

/**
 * Exception for external service communication failures.
 * <p>
 * Carries an {@link ErrorCategory} so handlers can emit a stable, low-cardinality
 * {@code error_type} metric tag without re-walking the cause chain at each site.
 * When not provided, the constructors infer the category from the inputs (HTTP
 * status -> HTTP_4XX/HTTP_5XX; cause chain -> TIMEOUT/CONNECT_REFUSED/CB_OPEN).
 */
@Getter
public class ExternalServiceException extends RuntimeException {

    private final String serviceName;
    private final Integer httpStatusCode;
    private final String responseBody;
    private final boolean retryable;
    private final ErrorCategory category;

    public ExternalServiceException(String serviceName, String message) {
        super(String.format("[%s] %s", serviceName, message));
        this.serviceName = serviceName;
        this.httpStatusCode = null;
        this.responseBody = null;
        this.retryable = true;
        this.category = ErrorCategory.UNKNOWN;
    }

    public ExternalServiceException(String serviceName, Exception cause) {
        super(String.format("[%s] %s", serviceName, cause.getMessage()), cause);
        this.serviceName = serviceName;
        this.httpStatusCode = null;
        this.responseBody = null;
        this.retryable = true;
        this.category = ErrorCategory.of(cause);
    }

    public ExternalServiceException(String serviceName, String message, Exception cause) {
        super(String.format("[%s] %s", serviceName, message), cause);
        this.serviceName = serviceName;
        this.httpStatusCode = null;
        this.responseBody = null;
        this.retryable = true;
        this.category = ErrorCategory.of(cause);
    }

    public ExternalServiceException(String serviceName, int httpStatusCode, String responseBody) {
        super(String.format("[%s] HTTP %d: %s", serviceName, httpStatusCode, responseBody));
        this.serviceName = serviceName;
        this.httpStatusCode = httpStatusCode;
        this.responseBody = responseBody;
        // 4xx errors (except 408, 429) are not retryable
        this.retryable = httpStatusCode >= 500 || httpStatusCode == 408 || httpStatusCode == 429;
        this.category = ErrorCategory.ofStatus(httpStatusCode);
    }

    /**
     * Explicit-category constructor for cases the inferrer cannot classify
     * (eg the fallback method observing CallNotPermittedException directly).
     */
    public ExternalServiceException(String serviceName, String message, Exception cause, ErrorCategory category) {
        super(String.format("[%s] %s", serviceName, message), cause);
        this.serviceName = serviceName;
        this.httpStatusCode = null;
        this.responseBody = null;
        this.retryable = category != ErrorCategory.HTTP_4XX;
        this.category = category;
    }
}
