package com.example.taskscheduler.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient configuration for external service calls.
 * <p>
 * Configures separate WebClient instances for order and payment services
 * with appropriate timeouts, logging, and error handling.
 */
@Configuration
@Slf4j
public class WebClientConfig {

    @Value("${external-services.order-service.base-url}")
    private String orderServiceBaseUrl;

    @Value("${external-services.order-service.timeout-seconds:30}")
    private int orderServiceTimeout;

    @Value("${external-services.payment-service.base-url}")
    private String paymentServiceBaseUrl;

    @Value("${external-services.payment-service.timeout-seconds:30}")
    private int paymentServiceTimeout;

    /**
     * Value surfaced to downstream services in the X-Service-Name header.
     * Sourced from spring.application.name so renaming the app stays in sync
     * with the header on every outbound call (alerting + downstream allow-lists
     * see the same identifier).
     */
    @Value("${spring.application.name:task-scheduler}")
    private String applicationName;

    /**
     * WebClient for Order Service API calls
     */
    @Bean(name = "orderServiceWebClient")
    public WebClient orderServiceWebClient(WebClient.Builder builder) {
        return createWebClient(builder, orderServiceBaseUrl, orderServiceTimeout, "OrderService");
    }

    /**
     * WebClient for Payment Service API calls
     */
    @Bean(name = "paymentServiceWebClient")
    public WebClient paymentServiceWebClient(WebClient.Builder builder) {
        return createWebClient(builder, paymentServiceBaseUrl, paymentServiceTimeout, "PaymentService");
    }

    /**
     * Generic WebClient for other API calls
     */
    @Bean(name = "genericWebClient")
    public WebClient genericWebClient(WebClient.Builder builder) {
        return createWebClient(builder, "", 30, "Generic");
    }

    private WebClient createWebClient(WebClient.Builder builder, String baseUrl, int timeoutSeconds, String serviceName) {
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));

        return builder.clone()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Service-Name", applicationName)
                .filter(logRequest(serviceName))
                .filter(logResponse(serviceName))
                .filter(handleErrors(serviceName))
                .build();
    }

    /**
     * Log outgoing requests
     */
    private ExchangeFilterFunction logRequest(String serviceName) {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            log.debug("[{}] Request: {} {}", serviceName, clientRequest.method(), clientRequest.url());
            return Mono.just(clientRequest);
        });
    }

    /**
     * Log incoming responses
     */
    private ExchangeFilterFunction logResponse(String serviceName) {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            log.debug("[{}] Response status: {}", serviceName, clientResponse.statusCode());
            return Mono.just(clientResponse);
        });
    }

    /**
     * Handle errors uniformly
     */
    private ExchangeFilterFunction handleErrors(String serviceName) {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (clientResponse.statusCode().isError()) {
                log.warn("[{}] Error response: {}", serviceName, clientResponse.statusCode());
            }
            return Mono.just(clientResponse);
        });
    }
}
