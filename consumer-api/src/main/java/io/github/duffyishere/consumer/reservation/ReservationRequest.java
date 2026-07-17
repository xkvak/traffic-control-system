package io.github.duffyishere.consumer.reservation;

public record ReservationRequest(
        String bookerName,
        Long seatId
) {}
