package io.github.duffyishere.consumer;

import io.github.duffyishere.consumer.seat.Seat;
import io.github.duffyishere.consumer.seat.SeatResponse;
import io.github.duffyishere.consumer.seat.SeatStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerApiApplicationTests {

    @Test
    void seatTransitionsBetweenAvailableAndReserved() {
        Seat seat = Seat.builder()
                .seatNo("A-01")
                .build();

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);

        seat.reserve();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.RESERVED);

        assertThatThrownBy(seat::reserve)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This seat has already been reserved");

        seat.cancel();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void seatResponseReflectsSeatState() {
        Seat seat = Seat.builder()
                .seatNo("B-03")
                .build();

        SeatResponse response = SeatResponse.from(seat);

        assertThat(response.id()).isNull();
        assertThat(response.seatNo()).isEqualTo("B-03");
        assertThat(response.status()).isEqualTo("AVAILABLE");
    }
}
