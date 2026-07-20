package io.github.duffyishere.turnstile.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueueNotificationTest {

    @Test
    void allowedCreatesAnAllowedNotification() {
        QueueNotification notification = QueueNotification.allowed("req-1", "token-1");

        assertThat(notification).isEqualTo(
                new QueueNotification("req-1", "ALLOWED", "token-1")
        );
    }
}
