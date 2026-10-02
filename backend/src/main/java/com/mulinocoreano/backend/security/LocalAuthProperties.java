package com.mulinocoreano.backend.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * PoC 로컬 신원 설정. workerToken이 비어 있으면 실행기 인증 경로를 열지 않는다.
 */
@ConfigurationProperties("mulino.local-auth")
@Validated
public record LocalAuthProperties(@NotBlank String issuer, String workerToken) {
    public LocalAuthProperties {
        issuer = issuer == null || issuer.isBlank() ? "https://local.mulino.test/" : issuer;
        workerToken = workerToken == null || workerToken.isBlank() ? null : workerToken;
    }
}
