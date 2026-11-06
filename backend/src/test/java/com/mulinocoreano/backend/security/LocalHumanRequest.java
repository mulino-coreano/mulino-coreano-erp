package com.mulinocoreano.backend.security;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** local 프로필의 호스트 인간 요청. 역할 헤더는 설정된 인간 secret과 함께만 인정된다. */
public final class LocalHumanRequest {
    public static final String SECRET = "local-test-human-secret";
    public static final String SECRET_PROPERTY = "mulino.local-auth.human-secret=" + SECRET;

    private LocalHumanRequest() {}

    public static RequestPostProcessor as(String role) {
        return request -> {
            request.addHeader(LocalActorFilter.ROLE_HEADER, role);
            request.addHeader(LocalActorFilter.HUMAN_SECRET_HEADER, SECRET);
            return request;
        };
    }
}
