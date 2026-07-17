package io.github.duffyishere.consumer.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReservationController(reservationService)).build();
    }

    @Test
    void reserveBindsBookerNameAndReturnsReservationId() throws Exception {
        when(reservationService.reserve(any(ReservationRequest.class))).thenReturn(42L);

        mockMvc.perform(post("/api/v1/reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bookerName": "홍길동",
                                  "seatId": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));

        ArgumentCaptor<ReservationRequest> requestCaptor = ArgumentCaptor.forClass(ReservationRequest.class);
        verify(reservationService).reserve(requestCaptor.capture());
        ReservationRequest request = requestCaptor.getValue();
        assertEquals("홍길동", request.bookerName());
        assertEquals(Long.valueOf(10L), request.seatId());
    }
}
