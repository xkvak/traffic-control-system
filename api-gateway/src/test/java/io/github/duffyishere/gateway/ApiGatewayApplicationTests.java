package io.github.duffyishere.gateway;

import io.github.duffyishere.gateway.common.AdmissionCheckGate;
import io.github.duffyishere.gateway.common.AdmissionRejectionCooldown;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiGatewayApplicationTests {

    private static final long REDIRECT_THRESHOLD = 2L;
    private static final String SEATS_PATH = "/api/v1/concerts/seats";

    @Test
    void allowsDirectAccessWhenAdmissionBucketAcceptsUnauthenticatedRequest() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = inactiveCooldown();
        AdmissionCheckGate checkGate = openGate();
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(tokenBucketResolver.tryConsumeAboveThreshold(REDIRECT_THRESHOLD)).thenReturn(Mono.just(Boolean.TRUE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGet(SEATS_PATH);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(tokenBucketResolver).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verify(checkGate).release();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void queuesUnauthenticatedRequestWhenAdmissionBucketRejectsRequest() throws Exception {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = inactiveCooldown();
        AdmissionCheckGate checkGate = openGate();
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(tokenBucketResolver.tryConsumeAboveThreshold(REDIRECT_THRESHOLD)).thenReturn(Mono.just(Boolean.FALSE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGet(SEATS_PATH + "?concertId=1");

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        var queryParams = queuePageQueryParams(exchange);
        assertThat(queryParams.getFirst("requestId")).isNotBlank();
        assertThat(org.springframework.web.util.UriUtils.decode(
                queryParams.getFirst("requestedUri"),
                StandardCharsets.UTF_8
        )).isEqualTo("/api/v1/concerts/seats?concertId=1");
        verify(cooldown).start();
        verify(checkGate).release();
    }

    @Test
    void queuesWithoutAdmissionBucketLookupWhenCheckGateRejectsRequest() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = inactiveCooldown();
        AdmissionCheckGate checkGate = mock(AdmissionCheckGate.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(checkGate.tryAcquire()).thenReturn(Boolean.FALSE);

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGet(SEATS_PATH);

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        verify(tokenBucketResolver, never()).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verify(checkGate).tryAcquire();
        verify(checkGate, never()).release();
        assertQueuedResponse(exchange);
    }

    @Test
    void queuesWithoutAdmissionBucketLookupWhenCooldownIsActive() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = mock(AdmissionRejectionCooldown.class);
        AdmissionCheckGate checkGate = openGate();
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(cooldown.isActive()).thenReturn(false, true);
        when(tokenBucketResolver.tryConsumeAboveThreshold(REDIRECT_THRESHOLD)).thenReturn(Mono.just(Boolean.FALSE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange firstExchange = exchangeForGet(SEATS_PATH);
        MockServerWebExchange secondExchange = exchangeForGet(SEATS_PATH);

        StepVerifier.create(filterFactory.apply(config()).filter(firstExchange, chain))
                .verifyComplete();
        StepVerifier.create(filterFactory.apply(config()).filter(secondExchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(firstExchange);
        verify(chain, never()).filter(secondExchange);
        verify(tokenBucketResolver, times(1)).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verify(cooldown, times(2)).isActive();
        verify(cooldown).start();
        verify(checkGate, times(1)).tryAcquire();
        verify(checkGate, times(1)).release();
        assertQueuedResponse(firstExchange);
        assertQueuedResponse(secondExchange);
    }

    @Test
    void retriesAdmissionBucketLookupAfterCooldownExpires() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = mock(AdmissionRejectionCooldown.class);
        AdmissionCheckGate checkGate = openGate();
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(cooldown.isActive()).thenReturn(false, false);
        when(tokenBucketResolver.tryConsumeAboveThreshold(REDIRECT_THRESHOLD))
                .thenReturn(Mono.just(Boolean.FALSE), Mono.just(Boolean.TRUE));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange firstExchange = exchangeForGet(SEATS_PATH);
        MockServerWebExchange secondExchange = exchangeForGet(SEATS_PATH);
        when(chain.filter(secondExchange)).thenReturn(Mono.empty());

        StepVerifier.create(filterFactory.apply(config()).filter(firstExchange, chain))
                .verifyComplete();
        StepVerifier.create(filterFactory.apply(config()).filter(secondExchange, chain))
                .verifyComplete();

        verify(chain).filter(secondExchange);
        verify(tokenBucketResolver, times(2)).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verify(cooldown).start();
        verify(checkGate, times(2)).release();
        assertQueuedResponse(firstExchange);
    }

    @Test
    void releasesCheckGateWhenAdmissionBucketLookupFails() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = inactiveCooldown();
        AdmissionCheckGate checkGate = openGate();
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(tokenBucketResolver.tryConsumeAboveThreshold(REDIRECT_THRESHOLD))
                .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGet(SEATS_PATH);

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        verify(checkGate).release();
        assertQueuedResponse(exchange);
    }

    @Test
    void queuesRequestWhenBearerTokenIsInvalid() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = inactiveCooldown();
        AdmissionCheckGate checkGate = mock(AdmissionCheckGate.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("invalid")));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGetWithBearer("broken-token");

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(exchange);
        verify(tokenBucketResolver, never()).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verifyNoInteractions(checkGate);
        assertQueuedResponse(exchange);
    }

    @Test
    void forwardsRequestWhenBearerTokenIsValid() {
        TokenBucketResolver tokenBucketResolver = mock(TokenBucketResolver.class);
        AdmissionRejectionCooldown cooldown = mock(AdmissionRejectionCooldown.class);
        AdmissionCheckGate checkGate = mock(AdmissionCheckGate.class);
        ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
        when(jwtDecoder.decode("valid-token")).thenReturn(Mono.just(Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("turnstile")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()));

        RateLimiterGatewayFilterFactory filterFactory = filterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = exchangeForGetWithBearer("valid-token");
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filterFactory.apply(config()).filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        verify(tokenBucketResolver, never()).tryConsumeAboveThreshold(REDIRECT_THRESHOLD);
        verifyNoInteractions(cooldown);
        verifyNoInteractions(checkGate);
    }

    private RateLimiterGatewayFilterFactory filterFactory(
            TokenBucketResolver tokenBucketResolver,
            AdmissionRejectionCooldown cooldown,
            AdmissionCheckGate checkGate,
            ReactiveJwtDecoder jwtDecoder
    ) {
        return new RateLimiterGatewayFilterFactory(tokenBucketResolver, cooldown, checkGate, jwtDecoder, true, REDIRECT_THRESHOLD);
    }

    private AdmissionRejectionCooldown inactiveCooldown() {
        AdmissionRejectionCooldown cooldown = mock(AdmissionRejectionCooldown.class);
        when(cooldown.isActive()).thenReturn(Boolean.FALSE);
        return cooldown;
    }

    private AdmissionCheckGate openGate() {
        AdmissionCheckGate checkGate = mock(AdmissionCheckGate.class);
        when(checkGate.tryAcquire()).thenReturn(Boolean.TRUE);
        return checkGate;
    }

    private RateLimiterGatewayFilterFactory.Config config() {
        return new RateLimiterGatewayFilterFactory.Config();
    }

    private MockServerWebExchange exchangeForGet(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private MockServerWebExchange exchangeForGetWithBearer(String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(SEATS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );
    }

    private void assertQueuedResponse(MockServerWebExchange exchange) {
        assertThat(extractJsonValue(queuePayload(exchange), "status")).isEqualTo("QUEUED");
    }

    private org.springframework.util.MultiValueMap<String, String> queuePageQueryParams(MockServerWebExchange exchange) {
        return UriComponentsBuilder.fromUriString(extractJsonValue(queuePayload(exchange), "queuePagePath"))
                .build()
                .getQueryParams();
    }

    private String queuePayload(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return responseBody(exchange);
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
