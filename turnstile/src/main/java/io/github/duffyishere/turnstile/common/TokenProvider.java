package io.github.duffyishere.turnstile.common;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Component
public class TokenProvider {

    @Value("${turnstile.token.ttl-seconds:60}")
    private long tokenTtlSeconds;

    private RSAKey rsaJWK;

    @PostConstruct
    public void initKey() throws JOSEException {
        this.rsaJWK = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .issueTime(new Date())
                .generate();
    }

    public Map<String, Object> getPublicKey() {
        JWKSet jwkSet = new JWKSet(rsaJWK.toPublicJWK());
        return jwkSet.toJSONObject(true);
    }

    public String generateToken(String requestId) throws JOSEException {
        Date issuedAt = new Date();
        Date expiresAt = new Date(issuedAt.getTime() + (tokenTtlSeconds * 1000));
        
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("turnstile")
                .claim("requestId", requestId)
                .issueTime(issuedAt)
                .expirationTime(expiresAt)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaJWK.getKeyID())
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(rsaJWK));

        return signedJWT.serialize();
    }
}
