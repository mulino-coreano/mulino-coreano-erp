package com.mulinocoreano.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 로컬 인간 신원만 구성한다. 실행기 토큰은 후속 lease 범위다. */
@ConfigurationProperties("mulino.local-auth")
public record LocalAuthProperties(String issuer, String serviceSecret) {
    public LocalAuthProperties {
        serviceSecret = serviceSecret == null || serviceSecret.isBlank() ? null : serviceSecret;
        issuer = issuer == null || issuer.isBlank() ? "https://local.mulino.test/" : issuer;
    }
}
