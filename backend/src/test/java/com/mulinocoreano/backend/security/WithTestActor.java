package com.mulinocoreano.backend.security;

import org.springframework.security.test.context.support.WithSecurityContext;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** 기존 도메인 HTTP 테스트용 이미 인증된 신원. 실제 필터·인가 규칙은 그대로 실행한다. */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = TestActorSecurityContextFactory.class)
public @interface WithTestActor {
    boolean service() default false;
    String role() default "MANAGER";
    String[] capabilities();
}
