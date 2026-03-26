package io.github.duffyishere.turnstile.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.redis.startup-check.enabled", havingValue = "true", matchIfMissing = true)
public class RedisStartupChecker implements ApplicationListener<ApplicationReadyEvent> {

    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Starting Redis startup checker");
        reactiveRedisTemplate.getConnectionFactory()
                .getReactiveConnection()
                .ping()
                .doOnSuccess(response -> log.info("Redis ping complete"))
                .doOnError(error -> log.error("Redis ping failed. ", error))
                .subscribe();
    }
}
