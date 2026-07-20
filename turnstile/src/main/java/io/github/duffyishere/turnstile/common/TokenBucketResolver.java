package io.github.duffyishere.turnstile.common;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class TokenBucketResolver {

    private static final long NO_TOKENS = 0L;

    private final LettuceBasedProxyManager<String> proxyManager;
    private final Supplier<CompletableFuture<BucketConfiguration>> bucketConfiguration;

    @Value("${rate-limiter.bucket.key}")
    private String rateLimiterBucketKey;

    private AsyncBucketProxy asyncBucket;

    @PostConstruct
    public void initializeBucket() {
        this.asyncBucket = proxyManager.asAsync().builder().build(rateLimiterBucketKey, bucketConfiguration);
    }

    public Mono<Long> consumeAvailable(long limit) {
        if (limit <= 0) {
            return Mono.just(NO_TOKENS);
        }

        return Mono.fromFuture(() -> asyncBucket.tryConsumeAsMuchAsPossible(limit))
                .defaultIfEmpty(NO_TOKENS);
    }

    public Mono<Long> getAvailableTokens() {
        return Mono.fromFuture(() -> asyncBucket.getAvailableTokens());
    }

    public Mono<Void> addTokens(long tokensToRefund) {
        if (tokensToRefund <= 0) {
            return Mono.empty();
        }

        return Mono.fromFuture(() -> asyncBucket.addTokens(tokensToRefund)).then();
    }
}
