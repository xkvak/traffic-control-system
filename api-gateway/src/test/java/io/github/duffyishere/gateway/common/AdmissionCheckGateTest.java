package io.github.duffyishere.gateway.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionCheckGateTest {

    @Test
    void rejectsWhenMaxInFlightChecksIsZero() {
        AdmissionCheckGate gate = new AdmissionCheckGate(0);

        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    void limitsConcurrentAcquisitionsAndAllowsRelease() {
        AdmissionCheckGate gate = new AdmissionCheckGate(2);

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();

        gate.release();

        assertThat(gate.tryAcquire()).isTrue();
    }
}
