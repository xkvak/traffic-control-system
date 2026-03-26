package io.github.duffyishere.turnstile.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/turnstile/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<QueueResponse>> subscribe(
            @RequestParam String requestId,
            @RequestParam(required = false) String requestedUri
    ) {
        return queueService.subscribeQueue(requestId, requestedUri)
                .map(data -> ServerSentEvent.<QueueResponse>builder()
                        .event(data.status().toLowerCase())
                        .data(data)
                        .build());
    }
}
