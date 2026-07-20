package io.github.duffyishere.turnstile.queue;

import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.github.duffyishere.turnstile.common.TokenProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {
    private static final String QUEUE_NAME = "queue";
    private static final String STATUS_WAITING = "WAITING";
    private static final long EMPTY_RANK = 0L;
    private static final int MAX_GRANT_CONCURRENCY = 64;

    private final QueueRepository queueRepository;
    private final TokenBucketResolver tokenBucketResolver;
    private final TokenProvider tokenProvider;

    private final ConcurrentMap<String, Sinks.One<QueueNotification>> channels = new ConcurrentHashMap<>();

    @Value("${turnstile.queue.dispatch-interval-millis:10}")
    private long dispatchIntervalMillis;

    @Value("${turnstile.queue.dispatch-max-batch:256}")
    private long dispatchMaxBatch;

    private Disposable dispatcher;

    @PostConstruct
    public void startDispatcher() {
        dispatcher = Flux.interval(Duration.ZERO, Duration.ofMillis(dispatchIntervalMillis))
                .onBackpressureDrop()
                .concatMap(ignored -> dispatch().onErrorResume(err -> {
                            log.error("Queue dispatcher failed", err);
                            return Mono.empty();
                        })
                ).subscribe(ignored -> {
                }, err -> log.error("Queue dispatcher terminated unexpectedly", err));
    }

    @PreDestroy
    public void stopDispatcher() {
        if (dispatcher != null && !dispatcher.isDisposed()) dispatcher.dispose();
    }

    private Mono<Void> dispatch() {
        return queueRepository.size(QUEUE_NAME)
                .map(queueSize -> Math.min(queueSize, dispatchMaxBatch))
                .filter(batchSize -> batchSize > 0)
                .flatMap(tokenBucketResolver::consumeAvailable)
                .filter(acquiredTokens -> acquiredTokens > 0)
                .flatMap(acquiredTokens -> queueRepository.popHead(QUEUE_NAME, acquiredTokens)
                        .collectList()
                        .flatMap(requestIds -> Flux.fromIterable(requestIds)
                                .flatMap(this::grant, MAX_GRANT_CONCURRENCY)
                                .then(tokenBucketResolver.addTokens(acquiredTokens - requestIds.size()))
                        ))
                .then();
    }

    private Mono<Void> grant(String requestId) {
        return Mono.fromCallable(() -> tokenProvider.generateToken(requestId))
                .subscribeOn(Schedulers.parallel())
                .flatMap(token -> {
                    QueueNotification notification = QueueNotification.allowed(requestId, token);
                    notifySubscriber(notification);
                    return Mono.empty();
                })
                .then()
                .onErrorResume(err -> {
                    log.error("Failed to grant requestId={}; requeueing", requestId, err);
                    Sinks.One<QueueNotification> channel = channels.get(requestId);
                    if (channel != null) {
                        Sinks.EmitResult result = channel.tryEmitError(new IllegalStateException("Queue grant failed"));
                        
                        if (result.isFailure())
                            log.debug("Failed to close SSE requestId={}: {}", requestId, result);
                    }

                    return Mono.empty();
                });
    }

    private void notifySubscriber(QueueNotification notification) {
        Sinks.One<QueueNotification> channel = channels.get(notification.requestId());
        if (channel == null) {
            log.debug("No active subscriber for requestId={}", notification.requestId());
            return;
        }

        Sinks.EmitResult result = channel.tryEmitValue(notification);
        if (result.isFailure())
            log.debug("Failed to emit notification for requestId={}: {}", notification.requestId(), result);
    }

    public Flux<QueueResponse> subscribeQueue(String requestUri) {
        String normalizedUri = requestUri == null || requestUri.isBlank() ? null : requestUri;

        return Flux.usingWhen(
                openSession(),
                session -> waitForGrant(session, normalizedUri),
                this::closeSession
        );
    }

    private Mono<QueueSession> openSession() {
        return Mono.fromSupplier(() -> {
            String requestId = UUID.randomUUID().toString();
            Sinks.One<QueueNotification> channel = Sinks.one();
            Sinks.One<QueueNotification> previous = channels.putIfAbsent(requestId, channel);
            if (previous != null)
                throw new IllegalStateException("Duplicate requestId: " + requestId);

            return new QueueSession(requestId, channel);
        });
    }

    private Flux<QueueResponse> waitForGrant(QueueSession session, String requestedUri) {
        return queueRepository.register(QUEUE_NAME, session.requestId())
                .thenMany(Flux.concat(queueRepository.getRank(QUEUE_NAME, session.requestId())
                                .map(rank -> new QueueResponse(STATUS_WAITING, rank + 1, null, null))
                                .flux(),
                        session.channel()
                                .asMono()
                                .map(notification -> new QueueResponse(notification.status(), EMPTY_RANK, notification.token(), requestedUri))
                                .flux()
                ));
    }

    private Mono<Void> closeSession(QueueSession session) {
        channels.remove(session.requestId(), session.channel());

        return queueRepository.remove(QUEUE_NAME, session.requestId())
                .then()
                .onErrorResume(err -> {
                    log.error("Failed to clean up requestId={}", session.requestId(), err);
                    return Mono.empty();
                });
    }
}
