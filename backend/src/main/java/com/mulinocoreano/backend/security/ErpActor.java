package com.mulinocoreano.backend.security;

import java.util.Set;

/** 서버가 검증한 신원. 요청 payload의 actor ID나 JWT 역할 claim을 권한으로 쓰지 않는다. */
public sealed interface ErpActor permits HumanActor, ServiceActor, AgentActor {
    String issuer();
    String subject();
    Set<String> capabilities();
}
