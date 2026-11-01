package com.mulinocoreano.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로컬 인간·실행기 신원 설정. 두 secret은 서로 다른 호스트 프로세스에만 둔다.
 * humanSecret은 호스트의 인간 클라이언트만 가지며 실행기와 모델 컨테이너에 전달하지 않는다.
 */
@ConfigurationProperties("mulino.local-auth")
public record LocalAuthProperties(String issuer, String serviceSecret, String humanSecret) {
    public LocalAuthProperties {
        serviceSecret = serviceSecret == null || serviceSecret.isBlank() ? null : serviceSecret;
        humanSecret = humanSecret == null || humanSecret.isBlank() ? null : humanSecret;
        issuer = issuer == null || issuer.isBlank() ? "https://local.mulino.test/" : issuer;
    }
}
