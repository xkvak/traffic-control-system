package io.github.duffyishere.gateway;

import io.github.duffyishere.gateway.common.TokenBucketResolver;
import io.github.duffyishere.gateway.filter.RateLimiterGatewayFilterFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayApplicationTests {

    @Test
    void allowsDirectAccessWhenAdmissionBucketAcceptsUnauthenticatedRequest() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(tokenBucketResolver.tryConsumeAboveThreshold(2L)).thenReturn(Mono.just(Boolean.TRUE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/concerts/seats").build()
        );
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(tokenBucketResolver).tryConsumeAboveThreshold(2L);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void queuesUnauthenticatedRequestWhenAdmissionBucketRejectsRequest() throws Exception {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(tokenBucketResolver.tryConsumeAboveThreshold(2L)).thenReturn(Mono.just(Boolean.FALSE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/concerts/seats?concertId=1").build()
        );

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String payload = responseBody(exchange);
        assertThat(extractJsonValue(payload, "status")).isEqualTo("QUEUED");
        var queryParams = UriComponentsBuilder.fromUriString(extractJsonValue(payload, "queuePagePath")).build().getQueryParams();
        assertThat(queryParams.getFirst("requestId")).isNotBlank();
        assertThat(org.springframework.web.util.UriUtils.decode(
                queryParams.getFirst("requestedUri"),
                StandardCharsets.UTF_8
        )).isEqualTo("/api/v1/concerts/seats?concertId=1");
    }

    @Test
    void queuesRequestWhenBearerTokenIsInvalid() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("invalid")));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/concerts/seats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer broken-token")
                        .build()
        );

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        verify(tokenBucketResolver, never()).tryConsumeAboveThreshold(2L);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void forwardsRequestWhenBearerTokenIsValid() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(jwtDecoder.decode("valid-token")).thenReturn(Mono.just(Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("turnstile")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/concerts/seats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build()
        );
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(tokenBucketResolver, never()).tryConsumeAboveThreshold(2L);
    }

    private RateLimiterGatewayFilterFactory filterFactory(
            TokenBucketResolver tokenBucketResolver,
            ReactiveJwtDecoder jwtDecoder
    ) {
        return new RateLimiterGatewayFilterFactory(tokenBucketResolver, jwtDecoder, true, 2L);
    }

    private RateLimiterGatewayFilterFactory.Config config() {
        return new RateLimiterGatewayFilterFactory.Config();
    }

    private String responseBody(MockServerWebExchange exchange) {
        DataBuffer bodyBuffer = DataBufferUtils.join(exchange.getResponse().getBody()).block(Duration.ofSeconds(1));
        assertThat(bodyBuffer).isNotNull();

        try {
            byte[] bytes = new byte[bodyBuffer.readableByteCount()];
            bodyBuffer.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(bodyBuffer);
        }
    }

    private String extractJsonValue(String body, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + fieldName + "\":\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
