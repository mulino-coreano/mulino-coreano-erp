package com.mulinocoreano.backend.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("mulino.local-auth")
@Validated
public record LocalAuthProperties(
        @NotBlank @Pattern(regexp = "ADMIN|MANAGER|OPERATOR|QC|VIEWER") String defaultRole,
        @NotBlank String issuer) {
    public LocalAuthProperties {
        defaultRole = defaultRole == null ? "MANAGER" : defaultRole;
        issuer = issuer == null || issuer.isBlank() ? "https://local.mulino.test/" : issuer;
    }
}
