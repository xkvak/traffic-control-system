package io.github.duffyishere.turnstile.queue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QueueNotificationTest {

    @Test
    void serializeAndDeserializeRoundTrip() {
        QueueNotification notification = QueueNotification.allowed("req-1", "token-1");

        Optional<QueueNotification> parsed = QueueNotification.deserialize(notification.serialize());

        assertThat(parsed).contains(notification);
    }

    @Test
    void deserializeRejectsMalformedPayload() {
        Optional<QueueNotification> parsed = QueueNotification.deserialize("req-1\tALLOWED");

        assertThat(parsed).isEmpty();
    }
}
