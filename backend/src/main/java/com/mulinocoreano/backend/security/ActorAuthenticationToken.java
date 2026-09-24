package com.mulinocoreano.backend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 인증 이후에는 bearer 원문을 신원 객체나 응답에 보관하지 않는다. */
public final class ActorAuthenticationToken extends AbstractAuthenticationToken {
    private final ErpActor actor;

    public ActorAuthenticationToken(ErpActor actor) {
        super(actor.capabilities().stream().map(SimpleGrantedAuthority::new).toList());
        this.actor = actor;
        super.setAuthenticated(true);
    }

    @Override public ErpActor getPrincipal() { return actor; }
    @Override public Object getCredentials() { return ""; }
    @Override public String getName() { return actor.subject(); }
}
