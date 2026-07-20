package io.github.duffyishere.turnstile.queue;

public record QueueNotification(
        String requestId,
        String status,
        String token
) {

    public static final String STATUS_ALLOWED = "ALLOWED";

    public static QueueNotification allowed(String requestId, String token) {
        return new QueueNotification(requestId, STATUS_ALLOWED, token);
    }
}
