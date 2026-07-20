package io.github.duffyishere.turnstile.queue;

import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.github.duffyishere.turnstile.common.TokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private QueueRepository queueRepository;

    @Mock
    private TokenBucketResolver tokenBucketResolver;

    @Mock
    private TokenProvider tokenProvider;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(
                queueRepository,
                tokenBucketResolver,
                tokenProvider
        );
        ReflectionTestUtils.setField(queueService, "dispatchIntervalMillis", 10L);
        ReflectionTestUtils.setField(queueService, "dispatchMaxBatch", 10L);
    }

    @AfterEach
    void tearDown() {
        queueService.stopDispatcher();
    }

    @Test
    void subscribeQueueRegistersGeneratedIdEmitsWaitingAndRemovesOnCancel() {
        when(queueRepository.register(eq("queue"), anyString())).thenReturn(Mono.just(true));
        when(queueRepository.getRank(eq("queue"), anyString())).thenReturn(Mono.just(2L));
        when(queueRepository.remove(eq("queue"), anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(queueService.subscribeQueue("/concerts"))
                .expectNextMatches(response ->
                        "WAITING".equals(response.status())
                                && response.rank() == 3L
                                && response.token() == null
                )
                .thenCancel()
                .verify();

        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(queueRepository).register(eq("queue"), requestIdCaptor.capture());

        String requestId = requestIdCaptor.getValue();
        assertThat(UUID.fromString(requestId)).isNotNull();
        verify(queueRepository).remove("queue", requestId);
    }

    @Test
    void subscribeQueueCreatesANewRequestIdForEveryConnection() {
        when(queueRepository.register(eq("queue"), anyString())).thenReturn(Mono.just(true));
        when(queueRepository.getRank(eq("queue"), anyString())).thenReturn(Mono.just(0L));
        when(queueRepository.remove(eq("queue"), anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(queueService.subscribeQueue("/concerts"))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        StepVerifier.create(queueService.subscribeQueue("/concerts"))
                .expectNextCount(1)
                .thenCancel()
                .verify();

        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(queueRepository, times(2)).register(eq("queue"), requestIdCaptor.capture());

        List<String> requestIds = requestIdCaptor.getAllValues();
        assertThat(requestIds).hasSize(2);
        assertThat(requestIds.get(0)).isNotEqualTo(requestIds.get(1));
    }

    @Test
    void subscribeQueueCleansUpWhenRegistrationFails() {
        AtomicReference<String> registeredRequestId = new AtomicReference<>();

        when(queueRepository.register(eq("queue"), anyString())).thenAnswer(invocation -> {
            registeredRequestId.set(invocation.getArgument(1));
            return Mono.error(new IllegalStateException("Redis unavailable"));
        });
        when(queueRepository.getRank(eq("queue"), anyString())).thenReturn(Mono.never());
        when(queueRepository.remove(eq("queue"), anyString())).thenReturn(Mono.just(0L));

        StepVerifier.create(queueService.subscribeQueue("/concerts"))
                .expectErrorMatches(error ->
                        error instanceof IllegalStateException
                                && "Redis unavailable".equals(error.getMessage())
                )
                .verify();

        verify(queueRepository).remove("queue", registeredRequestId.get());
    }

    @Test
    void dispatcherEmitsAllowedAndRefundsUnusedTokens() throws Exception {
        AtomicReference<String> registeredRequestId = new AtomicReference<>();
        AtomicBoolean rankRead = new AtomicBoolean(false);
        AtomicBoolean popped = new AtomicBoolean(false);

        when(queueRepository.register(eq("queue"), anyString())).thenAnswer(invocation -> {
            registeredRequestId.set(invocation.getArgument(1));
            return Mono.just(true);
        });
        when(queueRepository.getRank(eq("queue"), anyString())).thenAnswer(invocation -> {
            rankRead.set(true);
            return Mono.just(0L);
        });
        when(queueRepository.remove(eq("queue"), anyString())).thenReturn(Mono.just(0L));
        when(queueRepository.size("queue")).thenAnswer(invocation ->
                Mono.just(rankRead.get() && !popped.get() ? 2L : 0L)
        );
        when(tokenBucketResolver.consumeAvailable(2L)).thenReturn(Mono.just(2L));
        when(queueRepository.popHead("queue", 2L)).thenAnswer(invocation -> {
            popped.set(true);
            return Flux.just(registeredRequestId.get());
        });
        when(tokenProvider.generateToken(anyString())).thenReturn("token-1");
        when(tokenBucketResolver.addTokens(1L)).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> {
                    queueService.startDispatcher();
                    return queueService.subscribeQueue("/concerts");
                })
                .expectSubscription()
                .expectNextMatches(response ->
                        "WAITING".equals(response.status())
                                && response.rank() == 1L
                )
                .thenAwait(Duration.ofMillis(20))
                .expectNextMatches(response ->
                        QueueNotification.STATUS_ALLOWED.equals(response.status())
                                && response.rank() == 0L
                                && "token-1".equals(response.token())
                                && "/concerts".equals(response.requestedUri())
                )
                .verifyComplete();

        verify(tokenProvider).generateToken(registeredRequestId.get());
        verify(tokenBucketResolver).addTokens(1L);
        verify(queueRepository).remove("queue", registeredRequestId.get());
    }
}
