package com.mulinocoreano.backend.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

import java.time.Duration;

final class ErpJwtDecoder {
    private ErpJwtDecoder() {}

    static JwtDecoder create(String issuer, String audience, String jwksUri) {
        // issuer discovery / JWKS 캐시·회전은 Spring Security/Nimbus가 담당한다.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = jwksUri == null || jwksUri.isBlank()
                    ? NimbusJwtDecoder.withIssuerLocation(issuer).jwsAlgorithm(SignatureAlgorithm.RS256).build()
                    : NimbusJwtDecoder.withJwkSetUri(jwksUri).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtIssuerValidator(issuer), new JwtTimestampValidator(Duration.ZERO), jwt -> {
                        if (jwt.getExpiresAt() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                                || jwt.getAudience() == null || !jwt.getAudience().contains(audience)
                                || !isStringOrAbsent(jwt.getClaims().get("scope"))
                                || !isStringOrAbsent(jwt.getClaims().get("gty"))
                                || !isStringOrAbsent(jwt.getClaims().get("azp"))
                                || !isStringOrAbsent(jwt.getClaims().get("client_id"))) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                    "Required access token claims are absent or invalid", null));
                        }
                        return OAuth2TokenValidatorResult.success();
                    }));
            return decoder;
        });
    }

    private static boolean isStringOrAbsent(Object value) { return value == null || value instanceof String; }
}
