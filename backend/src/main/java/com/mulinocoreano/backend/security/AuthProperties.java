package com.mulinocoreano.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("mulino.auth")
@Validated
public record AuthProperties(
        @NotBlank(message = "MULINO_AUTH_ISSUER is required; configure the Auth0 HTTPS issuer")
        @Pattern(regexp = "https://[^\\s]+", message = "MULINO_AUTH_ISSUER must use HTTPS")
        String issuer,
        @NotBlank(message = "MULINO_API_AUDIENCE is required") String audience,
        @Pattern(regexp = "^$|https://[^\\s]+", message = "MULINO_AUTH_JWKS_URI must use HTTPS when configured")
        String jwksUri,
        String workerClientId
) {
    public AuthProperties {
        jwksUri = jwksUri == null ? "" : jwksUri;
        workerClientId = workerClientId == null ? "" : workerClientId;
    }
}
