package io.github.duffyishere.gateway.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Component
public class AdmissionRejectionCooldown {

    private final AtomicLong cooldownUntilNanos = new AtomicLong(Long.MIN_VALUE);
    private LongSupplier nanoTimeSupplier = System::nanoTime;
    private long cooldownNanos;

    @Value("${rate-limiter.bucket.rejection-cooldown-millis:-1}")
    private long rejectionCooldownMillis;

    @Value("${rate-limiter.bucket.refill-interval-seconds}")
    private long refillIntervalSeconds;

    public AdmissionRejectionCooldown() {
    }

    AdmissionRejectionCooldown(
            long rejectionCooldownMillis,
            long refillIntervalSeconds,
            LongSupplier nanoTimeSupplier
    ) {
        this.rejectionCooldownMillis = rejectionCooldownMillis;
        this.refillIntervalSeconds = refillIntervalSeconds;
        this.cooldownNanos = resolveCooldownNanos(rejectionCooldownMillis, refillIntervalSeconds);
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    @PostConstruct
    void initialize() {
        this.cooldownNanos = resolveCooldownNanos(rejectionCooldownMillis, refillIntervalSeconds);
    }

    public boolean isActive() {
        long cooldownUntil = cooldownUntilNanos.get();
        return cooldownUntil != Long.MIN_VALUE && nanoTimeSupplier.getAsLong() - cooldownUntil < 0;
    }

    public void start() {
        if (cooldownNanos <= 0) {
            return;
        }

        long cooldownUntil = nanoTimeSupplier.getAsLong() + cooldownNanos;
        cooldownUntilNanos.accumulateAndGet(cooldownUntil, Math::max);
    }

    private static long resolveCooldownNanos(long rejectionCooldownMillis, long refillIntervalSeconds) {
        if (rejectionCooldownMillis >= 0) {
            return TimeUnit.MILLISECONDS.toNanos(rejectionCooldownMillis);
        }

        return TimeUnit.SECONDS.toNanos(Math.max(0L, refillIntervalSeconds));
    }
}
