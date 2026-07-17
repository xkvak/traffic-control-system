package io.github.duffyishere.consumer.seat;

import io.github.duffyishere.consumer.reservation.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatServiceTest {

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private ReservationRepository reservationRepository;

    private SeatService seatService;

    @BeforeEach
    void setUp() {
        seatService = new SeatService(seatRepository, reservationRepository);
    }

    @Test
    void resetReservedSeatsCancelsOnlyReservedSeatsAndDeletesAllReservations() {
        Seat reservedSeat = mock(Seat.class);
        Seat availableSeat = mock(Seat.class);
        Seat soldSeat = mock(Seat.class);
        when(reservedSeat.getStatus()).thenReturn(SeatStatus.RESERVED);
        when(availableSeat.getStatus()).thenReturn(SeatStatus.AVAILABLE);
        when(soldSeat.getStatus()).thenReturn(SeatStatus.SOLD);
        when(seatRepository.findAllWithLock()).thenReturn(List.of(reservedSeat, availableSeat, soldSeat));

        seatService.resetReservedSeats();

        verify(seatRepository).findAllWithLock();
        verify(reservedSeat).cancel();
        verify(availableSeat, never()).cancel();
        verify(soldSeat, never()).cancel();
        verify(reservationRepository).deleteAllInBatch();
    }

    @Test
    void resetReservedSeatsIsIdempotentWhenSeatListIsEmpty() {
        when(seatRepository.findAllWithLock()).thenReturn(List.of());

        seatService.resetReservedSeats();
        seatService.resetReservedSeats();

        verify(seatRepository, times(2)).findAllWithLock();
        verify(reservationRepository, times(2)).deleteAllInBatch();
    }
}
