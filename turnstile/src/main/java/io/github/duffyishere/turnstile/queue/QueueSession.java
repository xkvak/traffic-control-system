package io.github.duffyishere.turnstile.queue;

import reactor.core.publisher.Sinks;

public record QueueSession(
        String requestId,
        Sinks.One<QueueNotification> channel
) {
}
