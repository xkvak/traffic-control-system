package io.github.duffyishere.gateway.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public class AdmissionCheckGate {

    @Value("${rate-limiter.bucket.max-in-flight-checks:100}")
    private int maxInFlightChecks;

    private Semaphore permits;

    public AdmissionCheckGate() {
    }

    AdmissionCheckGate(int maxInFlightChecks) {
        this.maxInFlightChecks = maxInFlightChecks;
        initialize();
    }

    @PostConstruct
    void initialize() {
        this.permits = new Semaphore(Math.max(0, maxInFlightChecks));
    }

    public boolean tryAcquire() {
        if (maxInFlightChecks <= 0) {
            return false;
        }

        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
