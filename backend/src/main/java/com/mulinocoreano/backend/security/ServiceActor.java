package com.mulinocoreano.backend.security;

import java.util.Set;

/** 서비스 신원에는 ERP userId/role을 절대로 부여하지 않는다. */
public record ServiceActor(String issuer, String subject, String clientId,
                           Set<String> capabilities) implements ErpActor {
    public ServiceActor { capabilities = Set.copyOf(capabilities); }
}
