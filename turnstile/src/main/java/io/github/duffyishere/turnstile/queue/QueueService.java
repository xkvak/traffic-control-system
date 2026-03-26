package io.github.duffyishere.turnstile.queue;

import com.nimbusds.jose.JOSEException;
import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.github.duffyishere.turnstile.common.TokenProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    private static final String QUEUE_NAME = "queue";
    private static final String STATUS_ALLOWED = "ALLOWED";
    private static final String STATUS_WAITING = "WAITING";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final long EMPTY_RANK = 0L;
    private static final long EXPIRED_RANK = -1L;
    private static final int MAX_GRANT_CONCURRENCY = 64;

    private final QueueRepository queueRepository;
    private final TokenBucketResolver tokenBucketResolver;
    private final TokenProvider tokenProvider;
    private final QueueNotificationBus queueNotificationBus;

    @Value("${turnstile.queue.dispatch-interval-millis:10}")
    private long dispatchIntervalMillis;

    @Value("${turnstile.queue.dispatch-max-batch:256}")
    private long dispatchMaxBatch;

    @Value("${turnstile.queue.status-refresh-seconds:15}")
    private long statusRefreshSeconds;

    @Value("${turnstile.queue.grant-ttl-seconds:60}")
    private long grantTtlSeconds;

    private Disposable dispatcherSubscription;
    private Duration dispatchInterval;
    private Duration statusRefreshInterval;
    private Duration grantTtl;

    @PostConstruct
    public void startDispatcher() {
        this.dispatchInterval = Duration.ofMillis(dispatchIntervalMillis);
        this.statusRefreshInterval = Duration.ofSeconds(statusRefreshSeconds);
        this.grantTtl = Duration.ofSeconds(grantTtlSeconds);

        this.dispatcherSubscription = Flux.interval(Duration.ZERO, dispatchInterval)
                .concatMap(ignored -> dispatchSafely())
                .subscribe(
                        ignored -> { },
                        error -> log.error("Dispatcher stream terminated unexpectedly", error)
                );
    }

    @PreDestroy
    public void stopDispatcher() {
        if (dispatcherSubscription != null && !dispatcherSubscription.isDisposed()) {
            dispatcherSubscription.dispose();
        }
    }

    private Mono<Void> dispatchSafely() {
        return dispatchOnce()
                .onErrorResume(error -> {
                    log.error("Dispatcher loop failed", error);
                    return Mono.empty();
                });
    }

    Mono<Void> dispatchOnce() {
        return queueRepository.size(QUEUE_NAME)
                .map(this::resolveDispatchBatchSize)
                .filter(this::hasWork)
                .flatMap(this::dispatchBatch)
                .then();
    }

    private long resolveDispatchBatchSize(long queueSize) {
        return Math.min(queueSize, dispatchMaxBatch);
    }

    private boolean hasWork(long count) {
        return count > 0;
    }

    private Mono<Void> dispatchBatch(long batchSize) {
        return tokenBucketResolver.consumeAvailable(batchSize)
                .filter(this::hasWork)
                .flatMap(acquiredTokens -> queueRepository.popHead(QUEUE_NAME, acquiredTokens)
                        .collectList()
                        .flatMap(requestIds -> grantRequests(requestIds, acquiredTokens)));
    }

    private Mono<Void> grantRequest(String requestId) {
        try {
            String token = tokenProvider.generateToken(requestId);
            return queueRepository.saveGrant(requestId, token, grantTtl)
                    .then(queueNotificationBus.publishAllowed(requestId, token))
                    .onErrorResume(error -> {
                        log.error("Failed to persist or publish queue grant for requestId={}", requestId, error);
                        return Mono.empty();
                    });
        } catch (JOSEException e) {
            log.error("Failed to generate token", e);
            return Mono.empty();
        }
    }

    private Mono<Void> grantRequests(List<String> requestIds, long acquiredTokens) {
        long unusedTokens = acquiredTokens - requestIds.size();

        return Flux.fromIterable(requestIds)
                .flatMap(this::grantRequest, Math.min(requestIds.size(), MAX_GRANT_CONCURRENCY))
                .then(refundUnusedTokens(unusedTokens));
    }

    private Mono<Void> refundUnusedTokens(long unusedTokens) {
        if (!hasWork(unusedTokens)) {
            return Mono.empty();
        }

        return tokenBucketResolver.addTokens(unusedTokens);
    }

    public Mono<QueueResponse> currentStatus(String requestId, String requestedUri) {
        return queueRepository.getGrant(requestId)
                .map(token -> allowedResponse(token, requestedUri))
                .switchIfEmpty(
                        queueRepository.getRank(QUEUE_NAME, requestId)
                                .map(this::waitingResponse)
                                .defaultIfEmpty(expiredResponse())
                );
    }

    public Flux<QueueResponse> subscribeQueue(String requestId, String requestedUri) {
        String normalizedRequestedUri = normalizeRequestedUri(requestedUri);
        Mono<String> resourceSupplier = queueRepository.register(QUEUE_NAME, requestId)
                .thenReturn(requestId);
        Function<String, Publisher<?>> asyncCleanup = id -> queueRepository.remove(QUEUE_NAME, id);

        return Flux.usingWhen(
                resourceSupplier,
                id -> statusStream(id, normalizedRequestedUri),
                asyncCleanup
        );
    }

    private Flux<QueueResponse> statusStream(String requestId, String normalizedRequestedUri) {
        Flux<QueueResponse> initialStatus = Flux.defer(() -> currentStatus(requestId, normalizedRequestedUri));
        Flux<QueueResponse> allowedNotifications = queueNotificationBus.notificationsFor(requestId)
                .map(notification -> notificationResponse(notification, normalizedRequestedUri));
        Flux<QueueResponse> fallbackStatusRefresh = Flux.interval(statusRefreshInterval, statusRefreshInterval)
                .concatMap(ignored -> currentStatus(requestId, normalizedRequestedUri));

        return Flux.merge(initialStatus, allowedNotifications, fallbackStatusRefresh)
                .distinctUntilChanged(this::statusFingerprint)
                .takeUntil(this::isTerminalStatus);
    }

    private QueueResponse notificationResponse(QueueNotification notification, String requestedUri) {
        return new QueueResponse(notification.status(), EMPTY_RANK, notification.token(), requestedUri);
    }

    private QueueResponse allowedResponse(String token, String requestedUri) {
        return new QueueResponse(STATUS_ALLOWED, EMPTY_RANK, token, normalizeRequestedUri(requestedUri));
    }

    private QueueResponse waitingResponse(long rank) {
        return new QueueResponse(STATUS_WAITING, rank + 1, null, null);
    }

    private QueueResponse expiredResponse() {
        return new QueueResponse(STATUS_EXPIRED, EXPIRED_RANK, null, null);
    }

    private boolean isTerminalStatus(QueueResponse response) {
        return STATUS_ALLOWED.equals(response.status()) || STATUS_EXPIRED.equals(response.status());
    }

    private String statusFingerprint(QueueResponse response) {
        return response.status() + "|" + response.rank() + "|" + response.token();
    }

    private String normalizeRequestedUri(String requestedUri) {
        if (requestedUri == null || requestedUri.isBlank()) {
            return null;
        }
        return requestedUri;
    }
}
