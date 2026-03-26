package io.github.duffyishere.turnstile.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class QueueNotificationBus {

    private static final String TOPIC_NAME = "queue:notifications";
    private static final ChannelTopic TOPIC = new ChannelTopic(TOPIC_NAME);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Flux<QueueNotification> notifications;

    public QueueNotificationBus(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ReactiveRedisMessageListenerContainer listenerContainer
    ) {
        this.redisTemplate = redisTemplate;
        this.notifications = listenerContainer.receive(TOPIC)
                .map(ReactiveSubscription.Message::getMessage)
                .flatMap(this::deserializeSafely)
                .publish()
                .refCount(1);
    }

    public Flux<QueueNotification> notificationsFor(String requestId) {
        return notifications.filter(notification -> requestId.equals(notification.requestId()));
    }

    public Mono<Void> publishAllowed(String requestId, String token) {
        return publish(QueueNotification.allowed(requestId, token));
    }

    private Mono<Void> publish(QueueNotification notification) {
        return redisTemplate.convertAndSend(TOPIC_NAME, notification.serialize()).then();
    }

    private Mono<QueueNotification> deserializeSafely(String payload) {
        return Mono.justOrEmpty(QueueNotification.deserialize(payload))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Skipping malformed queue notification payload");
                    return Mono.empty();
                }));
    }
}
