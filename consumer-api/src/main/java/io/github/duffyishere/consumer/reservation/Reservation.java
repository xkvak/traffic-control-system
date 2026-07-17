package io.github.duffyishere.consumer.reservation;

import io.github.duffyishere.consumer.seat.Seat;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booker_name", length = 100)
    private String bookerName;

    private LocalDateTime reservedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Builder
    public Reservation(String bookerName, Seat seat) {
        this.bookerName = bookerName;
        this.seat = seat;
        this.reservedDate = LocalDateTime.now();
    }
}
