package io.github.duffyishere.turnstile.metrics;

import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurnstileMetricsTest {

    @Mock
    private TokenBucketResolver tokenBucketResolver;

    private SimpleMeterRegistry meterRegistry;
    private TurnstileMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new TurnstileMetrics(
                tokenBucketResolver,
                meterRegistry,
                "admission",
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
    }

    @Test
    void recordsAvailableTokenCount() {
        when(tokenBucketResolver.getAvailableTokens()).thenReturn(Mono.just(73L));

        StepVerifier.create(metrics.sampleAvailableTokens())
                .verifyComplete();

        assertThat(availableTokens()).isEqualTo(73);
    }

    @Test
    void marksTokenMetricUnknownAndRecoversAfterRedisFailure() {
        when(tokenBucketResolver.getAvailableTokens())
                .thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));

        StepVerifier.create(metrics.sampleAvailableTokens())
                .verifyComplete();
        assertThat(availableTokens()).isEqualTo(-1);

        when(tokenBucketResolver.getAvailableTokens()).thenReturn(Mono.just(55L));

        StepVerifier.create(metrics.sampleAvailableTokens())
                .verifyComplete();
        assertThat(availableTokens()).isEqualTo(55);
    }

    @Test
    void recordsQueueSizeWithoutRedisLookup() {
        metrics.recordQueueSize(42L);

        double value = meterRegistry
                .get(TurnstileMetrics.QUEUE_SIZE_METRIC)
                .tag("queue", "queue")
                .gauge()
                .value();

        assertThat(value).isEqualTo(42);
    }

    private double availableTokens() {
        return meterRegistry
                .get(TurnstileMetrics.AVAILABLE_TOKENS_METRIC)
                .tag("bucket", "admission")
                .gauge()
                .value();
    }
}
