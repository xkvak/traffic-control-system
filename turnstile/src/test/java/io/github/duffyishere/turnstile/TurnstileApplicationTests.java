package io.github.duffyishere.turnstile;

import com.nimbusds.jwt.SignedJWT;
import io.github.duffyishere.turnstile.common.TokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TurnstileApplicationTests {

    @Test
    void generatedTokensExpireAccordingToConfiguredTtl() throws Exception {
        TokenProvider tokenProvider = new TokenProvider();
        ReflectionTestUtils.setField(tokenProvider, "tokenTtlSeconds", 45L);
        tokenProvider.initKey();

        String token = tokenProvider.generateToken("request-123");
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("turnstile");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("requestId")).isEqualTo("request-123");
        assertThat(parsed.getJWTClaimsSet().getExpirationTime()).isNotNull();
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().getTime()
                - parsed.getJWTClaimsSet().getIssueTime().getTime())
                .isEqualTo(45_000L);
    }
}
