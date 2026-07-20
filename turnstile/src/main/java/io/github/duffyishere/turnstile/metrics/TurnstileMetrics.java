package io.github.duffyishere.turnstile.metrics;

import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class TurnstileMetrics {

    static final String AVAILABLE_TOKENS_METRIC = "turnstile.admission.tokens.available";
    static final String QUEUE_SIZE_METRIC = "turnstile.queue.size";

    private static final long UNKNOWN_VALUE = -1L;

    private final TokenBucketResolver tokenBucketResolver;
    private final Duration tokenSampleInterval;
    private final Duration tokenSampleTimeout;
    private final AtomicLong availableTokens = new AtomicLong(UNKNOWN_VALUE);
    private final AtomicLong queueSize = new AtomicLong(UNKNOWN_VALUE);
    private final AtomicBoolean tokenSampleFailed = new AtomicBoolean(false);

    private Disposable tokenSampler;

    public TurnstileMetrics(
            TokenBucketResolver tokenBucketResolver,
            MeterRegistry meterRegistry,
            @Value("${rate-limiter.bucket.key}") String bucketName,
            @Value("${turnstile.metrics.token-sample-interval:5s}") Duration tokenSampleInterval,
            @Value("${turnstile.metrics.token-sample-timeout:1s}") Duration tokenSampleTimeout
    ) {
        this.tokenBucketResolver = tokenBucketResolver;
        this.tokenSampleInterval = tokenSampleInterval;
        this.tokenSampleTimeout = tokenSampleTimeout;

        Gauge.builder(AVAILABLE_TOKENS_METRIC, availableTokens, AtomicLong::get)
                .description("Available tokens in the shared admission bucket")
                .tag("bucket", bucketName)
                .register(meterRegistry);

        Gauge.builder(QUEUE_SIZE_METRIC, queueSize, AtomicLong::get)
                .description("Number of requests waiting in the turnstile queue")
                .tag("queue", "queue")
                .register(meterRegistry);
    }

    @PostConstruct
    void startTokenSampler() {
        tokenSampler = Flux.interval(Duration.ZERO, tokenSampleInterval)
                .onBackpressureDrop()
                .concatMap(ignored -> sampleAvailableTokens())
                .subscribe(
                        ignored -> { },
                        error -> log.error("Admission token metric sampler stopped unexpectedly", error)
                );
    }

    Mono<Void> sampleAvailableTokens() {
        return tokenBucketResolver.getAvailableTokens()
                .timeout(tokenSampleTimeout)
                .doOnNext(this::recordAvailableTokens)
                .then()
                .onErrorResume(this::handleTokenSampleFailure);
    }

    public void recordQueueSize(long currentQueueSize) {
        queueSize.set(currentQueueSize);
    }

    public void markQueueSizeUnavailable() {
        queueSize.set(UNKNOWN_VALUE);
    }

    private void recordAvailableTokens(long tokens) {
        availableTokens.set(tokens);

        if (tokenSampleFailed.compareAndSet(true, false)) {
            log.info("Admission token metric sampling recovered");
        }
    }

    private Mono<Void> handleTokenSampleFailure(Throwable error) {
        availableTokens.set(UNKNOWN_VALUE);

        if (tokenSampleFailed.compareAndSet(false, true)) {
            log.warn("Failed to sample admission token count", error);
        }

        return Mono.empty();
    }

    @PreDestroy
    void stopTokenSampler() {
        if (tokenSampler != null && !tokenSampler.isDisposed()) {
            tokenSampler.dispose();
        }
    }
}
