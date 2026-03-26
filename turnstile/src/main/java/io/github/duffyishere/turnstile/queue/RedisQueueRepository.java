package io.github.duffyishere.turnstile.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class RedisQueueRepository implements QueueRepository {

    private static final String GRANT_PREFIX = "queue:grant:";
    private static final String SEQUENCE_PREFIX = "queue:sequence:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Override
    public Mono<Boolean> register(String queueName, String requestId) {
        return isRegistered(queueName, requestId)
                .flatMap(alreadyRegistered -> {
                    if (alreadyRegistered) {
                        return Mono.just(Boolean.FALSE);
                    }

                    return nextSequence(queueName)
                            .flatMap(sequence -> redisTemplate.opsForZSet()
                                    .add(queueName, requestId, sequence.doubleValue()));
                });
    }

    @Override
    public Mono<Long> getRank(String queueName, String requestId) {
        return redisTemplate.opsForZSet().rank(queueName, requestId);
    }

    @Override
    public Mono<Long> remove(String queueName, String requestId) {
        return redisTemplate.opsForZSet().remove(queueName, requestId);
    }

    @Override
    public Mono<Long> size(String queueName) {
        return redisTemplate.opsForZSet().size(queueName);
    }

    @Override
    public Flux<String> popHead(String queueName, long count) {
        return redisTemplate.opsForZSet()
                .popMin(queueName, count)
                .map(tuple -> tuple.getValue());
    }

    @Override
    public Mono<Void> saveGrant(String requestId, String token, Duration ttl) {
        return redisTemplate.opsForValue()
                .set(grantKey(requestId), token, ttl)
                .then();
    }

    @Override
    public Mono<String> getGrant(String requestId) {
        return redisTemplate.opsForValue().get(grantKey(requestId));
    }

    private Mono<Boolean> isRegistered(String queueName, String requestId) {
        return redisTemplate.opsForZSet().score(queueName, requestId).hasElement();
    }

    private Mono<Long> nextSequence(String queueName) {
        return redisTemplate.opsForValue().increment(sequenceKey(queueName));
    }

    private String grantKey(String requestId) {
        return GRANT_PREFIX + requestId;
    }

    private String sequenceKey(String queueName) {
        return SEQUENCE_PREFIX + queueName;
    }
}
