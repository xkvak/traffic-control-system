package io.github.duffyishere.turnstile.queue;

import io.github.duffyishere.turnstile.common.TokenBucketResolver;
import io.github.duffyishere.turnstile.common.TokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Mock
    private QueueNotificationBus queueNotificationBus;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        queueService = new QueueService(queueRepository, tokenBucketResolver, tokenProvider, queueNotificationBus);
        ReflectionTestUtils.setField(queueService, "dispatchMaxBatch", 10L);
        ReflectionTestUtils.setField(queueService, "grantTtl", Duration.ofSeconds(60));
    }

    @Test
    void dispatchOnceGrantsBatchAndRefundsUnusedTokens() throws Exception {
        when(queueRepository.size("queue")).thenReturn(Mono.just(5L));
        when(tokenBucketResolver.consumeAvailable(5L)).thenReturn(Mono.just(3L));
        when(queueRepository.popHead("queue", 3L)).thenReturn(Flux.just("req-1", "req-2"));
        when(tokenProvider.generateToken("req-1")).thenReturn("token-1");
        when(tokenProvider.generateToken("req-2")).thenReturn("token-2");
        when(queueRepository.saveGrant(eq("req-1"), eq("token-1"), any(Duration.class))).thenReturn(Mono.empty());
        when(queueRepository.saveGrant(eq("req-2"), eq("token-2"), any(Duration.class))).thenReturn(Mono.empty());
        when(queueNotificationBus.publishAllowed("req-1", "token-1")).thenReturn(Mono.empty());
        when(queueNotificationBus.publishAllowed("req-2", "token-2")).thenReturn(Mono.empty());
        when(tokenBucketResolver.addTokens(1L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.dispatchOnce())
                .verifyComplete();

        verify(queueRepository).popHead("queue", 3L);
        verify(queueRepository).saveGrant("req-1", "token-1", Duration.ofSeconds(60));
        verify(queueRepository).saveGrant("req-2", "token-2", Duration.ofSeconds(60));
        verify(queueNotificationBus).publishAllowed("req-1", "token-1");
        verify(queueNotificationBus).publishAllowed("req-2", "token-2");
        verify(tokenBucketResolver).addTokens(1L);
    }

    @Test
    void dispatchOnceDoesNothingWhenQueueIsEmpty() {
        when(queueRepository.size("queue")).thenReturn(Mono.just(0L));

        StepVerifier.create(queueService.dispatchOnce())
                .verifyComplete();

        verify(tokenBucketResolver, never()).consumeAvailable(anyLong());
        verify(queueRepository, never()).popHead(any(), anyLong());
    }
}
