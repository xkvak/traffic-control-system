package io.github.duffyishere.consumer.reservation;

import io.github.duffyishere.consumer.seat.Seat;
import io.github.duffyishere.consumer.seat.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatRepository seatRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(reservationRepository, seatRepository);
    }

    @Test
    void reserveStoresTrimmedBookerName() {
        Seat seat = mock(Seat.class);
        Reservation savedReservation = mock(Reservation.class);
        when(seatRepository.findByIdWithLock(10L)).thenReturn(Optional.of(seat));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
        when(savedReservation.getId()).thenReturn(42L);

        Long reservationId = reservationService.reserve(new ReservationRequest("  홍길동  ", 10L));

        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(reservationCaptor.capture());
        Reservation reservation = reservationCaptor.getValue();
        assertEquals(Long.valueOf(42L), reservationId);
        assertEquals("홍길동", reservation.getBookerName());
        assertSame(seat, reservation.getSeat());
        verify(seat).reserve();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t\n"})
    void reserveRejectsBlankBookerName(String bookerName) {
        ReservationRequest request = new ReservationRequest(bookerName, 10L);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserve(request));

        verifyNoInteractions(seatRepository, reservationRepository);
    }

    @Test
    void reserveRejectsBookerNameLongerThanOneHundredCharacters() {
        ReservationRequest request = new ReservationRequest("가".repeat(101), 10L);

        assertThrows(IllegalArgumentException.class, () -> reservationService.reserve(request));

        verifyNoInteractions(seatRepository, reservationRepository);
    }
}
