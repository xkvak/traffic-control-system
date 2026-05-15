package io.github.duffyishere.gateway.filter;

import io.github.duffyishere.gateway.common.AdmissionCheckGate;
import io.github.duffyishere.gateway.common.AdmissionRejectionCooldown;
import io.github.duffyishere.gateway.common.TokenBucketResolver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class RateLimiterGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimiterGatewayFilterFactory.Config> {

    private static final String CURRENT_PAGE_URI_HEADER = "X-Current-Page-Uri";
    private static final String CURRENT_PAGE_URI_PARAM = "currentPageUri";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String QUEUE_STATUS = "QUEUED";
    private static final String QUEUE_PAGE_PATH = "/queue";
    private static final String QUEUE_SSE_PATH = "/turnstile/queue/events";

    private final TokenBucketResolver tokenBucketResolver;
    private final AdmissionRejectionCooldown admissionRejectionCooldown;
    private final AdmissionCheckGate admissionCheckGate;
    private final ReactiveJwtDecoder jwtDecoder;
    private final boolean rateLimiterEnabled;
    private final long redirectThreshold;

    public RateLimiterGatewayFilterFactory(
            TokenBucketResolver tokenBucketResolver,
            AdmissionRejectionCooldown admissionRejectionCooldown,
            AdmissionCheckGate admissionCheckGate,
            ReactiveJwtDecoder jwtDecoder,
            @Value("${rate-limiter.enabled:true}") boolean rateLimiterEnabled,
            @Value("${rate-limiter.bucket.redirect-threshold}") long redirectThreshold
    ) {
        super(Config.class);
        this.tokenBucketResolver = tokenBucketResolver;
        this.admissionRejectionCooldown = admissionRejectionCooldown;
        this.admissionCheckGate = admissionCheckGate;
        this.jwtDecoder = jwtDecoder;
        this.rateLimiterEnabled = rateLimiterEnabled;
        this.redirectThreshold = redirectThreshold;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (!rateLimiterEnabled) {
                return chain.filter(exchange);
            }

            return extractBearerToken(exchange)
                    .map(token -> validateQueueToken(token, exchange, chain))
                    .orElseGet(() -> applyAdmissionControl(exchange, chain));
        };
    }

    private Mono<Void> validateQueueToken(
            String token,
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {
        return jwtDecoder.decode(token)
                .flatMap(jwt -> chain.filter(exchange))
                .onErrorResume(error -> {
                    log.debug("Queue response because token validation failed: {}", error.getMessage());
                    return enqueueRequest(exchange);
                });
    }

    private Mono<Void> applyAdmissionControl(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {
        if (admissionRejectionCooldown.isActive()) {
            return enqueueRequest(exchange);
        }

        if (!admissionCheckGate.tryAcquire()) {
            return enqueueRequest(exchange);
        }

        return Mono.defer(() -> tokenBucketResolver.tryConsumeAboveThreshold(redirectThreshold))
                .doFinally(ignored -> admissionCheckGate.release())
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    }

                    admissionRejectionCooldown.start();
                    return enqueueRequest(exchange);
                })
                .onErrorResume(error -> {
                    log.warn("Queue response because admission check failed: {}", error.getMessage());
                    return enqueueRequest(exchange);
                });
    }

    private Mono<Void> enqueueRequest(ServerWebExchange exchange) {
        String requestId = UUID.randomUUID().toString();
        String requestedUri = resolveRequestedUri(exchange);
        QueueAdmissionResponse payload = new QueueAdmissionResponse(
                QUEUE_STATUS,
                requestId,
                requestedUri,
                buildQueuePath(QUEUE_PAGE_PATH, requestId, requestedUri),
                buildQueuePath(QUEUE_SSE_PATH, requestId, requestedUri)
        );

        byte[] body = serializeQueueResponse(payload);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.ACCEPTED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().set("X-Queue-Request-Id", requestId);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private String buildQueuePath(String basePath, String requestId, String requestedUri) {
        UriComponentsBuilder locationBuilder = UriComponentsBuilder.fromPath(basePath)
                .queryParam("requestId", requestId);

        if (requestedUri != null) {
            locationBuilder.queryParam("requestedUri", UriUtils.encode(requestedUri, StandardCharsets.UTF_8));
        }

        return locationBuilder.build(true).toUriString();
    }

    private byte[] serializeQueueResponse(QueueAdmissionResponse payload) {
        String body = """
                {"status":"%s","requestId":"%s","requestedUri":"%s","queuePagePath":"%s","queueSsePath":"%s"}
                """.formatted(
                escapeJson(payload.status()),
                escapeJson(payload.requestId()),
                escapeJson(payload.requestedUri()),
                escapeJson(payload.queuePagePath()),
                escapeJson(payload.queueSsePath())
        );
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private Optional<String> extractBearerToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }

        return Optional.of(authHeader.substring(BEARER_PREFIX.length()));
    }

    private String resolveRequestedUri(ServerWebExchange exchange) {
        String currentPageUri = exchange.getRequest().getHeaders().getFirst(CURRENT_PAGE_URI_HEADER);
        if (!StringUtils.hasText(currentPageUri)) {
            currentPageUri = exchange.getRequest().getQueryParams().getFirst(CURRENT_PAGE_URI_PARAM);
        }

        URI currentPage = parseUriOrNull(currentPageUri);
        if (currentPage != null) {
            return normalizePathAndQuery(currentPage);
        }

        return normalizePathAndQuery(exchange.getRequest().getURI());
    }

    private URI parseUriOrNull(String uriValue) {
        if (!StringUtils.hasText(uriValue)) {
            return null;
        }

        try {
            return URI.create(uriValue);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid current page uri value: {}", uriValue);
            return null;
        }
    }

    private String normalizePathAndQuery(URI uri) {
        if (uri == null) {
            return "/";
        }

        String path = uri.getRawPath();
        if (!StringUtils.hasText(path)) {
            return "/";
        }

        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    @Data
    public static class Config {
    }

    private record QueueAdmissionResponse(
            String status,
            String requestId,
            String requestedUri,
            String queuePagePath,
            String queueSsePath
    ) {
    }
}
