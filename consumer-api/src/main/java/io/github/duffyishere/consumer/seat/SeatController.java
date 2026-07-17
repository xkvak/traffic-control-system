package io.github.duffyishere.consumer.seat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/concerts")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping("/seats")
    public ResponseEntity<List<SeatResponse>> getSeats() {
        List<SeatResponse> seats = seatService.getAllSeats();
        return ResponseEntity.ok(seats);
    }

    @DeleteMapping("/seats")
    public ResponseEntity<Void> resetReservedSeats() {
        seatService.resetReservedSeats();
        return ResponseEntity.noContent().build();
    }
}
