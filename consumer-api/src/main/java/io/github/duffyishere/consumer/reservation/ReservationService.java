package io.github.duffyishere.consumer.reservation;

import io.github.duffyishere.consumer.seat.Seat;
import io.github.duffyishere.consumer.seat.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final int MAX_BOOKER_NAME_LENGTH = 100;

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;

    @Transactional
    public Long reserve(ReservationRequest request) {
        String bookerName = normalizeBookerName(request.bookerName());
        Seat seat = seatRepository.findByIdWithLock(request.seatId())
                .orElseThrow(() -> new IllegalArgumentException("There is no seat available for this seat"));
        seat.reserve();
        Reservation reservation = reservationRepository.save(
                Reservation.builder()
                        .bookerName(bookerName)
                        .seat(seat)
                        .build()
        );

        return reservation.getId();
    }

    private String normalizeBookerName(String bookerName) {
        if (bookerName == null || bookerName.isBlank()) {
            throw new IllegalArgumentException("예매자 이름을 입력해 주세요.");
        }

        String normalizedBookerName = bookerName.strip();
        if (normalizedBookerName.length() > MAX_BOOKER_NAME_LENGTH) {
            throw new IllegalArgumentException("예매자 이름은 100자 이하로 입력해 주세요.");
        }
        return normalizedBookerName;
    }
}
