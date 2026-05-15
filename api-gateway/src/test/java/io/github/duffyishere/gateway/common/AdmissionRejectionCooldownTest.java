package io.github.duffyishere.gateway.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionRejectionCooldownTest {

    @Test
    void remainsActiveUntilConfiguredCooldownMillisElapses() {
        AtomicLong nowNanos = new AtomicLong(0L);
        AdmissionRejectionCooldown cooldown = new AdmissionRejectionCooldown(100L, 1L, nowNanos::get);

        cooldown.start();

        assertThat(cooldown.isActive()).isTrue();

        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(99L));
        assertThat(cooldown.isActive()).isTrue();

        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(100L));
        assertThat(cooldown.isActive()).isFalse();
    }

    @Test
    void usesRefillIntervalWhenConfiguredCooldownIsNegative() {
        AtomicLong nowNanos = new AtomicLong(0L);
        AdmissionRejectionCooldown cooldown = new AdmissionRejectionCooldown(-1L, 2L, nowNanos::get);

        cooldown.start();

        nowNanos.set(TimeUnit.SECONDS.toNanos(2L) - 1L);
        assertThat(cooldown.isActive()).isTrue();

        nowNanos.set(TimeUnit.SECONDS.toNanos(2L));
        assertThat(cooldown.isActive()).isFalse();
    }

    @Test
    void zeroConfiguredCooldownDoesNotBecomeActive() {
        AtomicLong nowNanos = new AtomicLong(0L);
        AdmissionRejectionCooldown cooldown = new AdmissionRejectionCooldown(0L, 1L, nowNanos::get);

        cooldown.start();

        assertThat(cooldown.isActive()).isFalse();
    }
}
