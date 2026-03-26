package io.github.duffyishere.turnstile.queue;

import java.util.Optional;

public record QueueNotification(
        String requestId,
        String status,
        String token
) {

    private static final String FIELD_DELIMITER = "\t";
    private static final int FIELD_COUNT = 3;
    public static final String STATUS_ALLOWED = "ALLOWED";

    public static QueueNotification allowed(String requestId, String token) {
        return new QueueNotification(requestId, STATUS_ALLOWED, token);
    }

    public static Optional<QueueNotification> deserialize(String payload) {
        String[] parts = payload.split(FIELD_DELIMITER, FIELD_COUNT);
        if (parts.length != FIELD_COUNT || hasBlankField(parts)) {
            return Optional.empty();
        }

        return Optional.of(new QueueNotification(parts[0], parts[1], parts[2]));
    }

    public String serialize() {
        return String.join(FIELD_DELIMITER, requestId, status, token);
    }

    private static boolean hasBlankField(String[] parts) {
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                return true;
            }
        }
        return false;
    }
}
