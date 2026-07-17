package io.github.duffyishere.consumer.seat;

import io.github.duffyishere.consumer.reservation.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    public List<SeatResponse> getAllSeats() {
        return seatRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(SeatResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void resetReservedSeats() {
        List<Seat> seats = seatRepository.findAllWithLock();

        reservationRepository.deleteAllInBatch();
        seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.RESERVED)
                .forEach(Seat::cancel);
    }
}
