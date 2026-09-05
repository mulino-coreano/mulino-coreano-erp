package com.mulinocoreano.backend.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import java.util.Set;

public class TestActorSecurityContextFactory implements WithSecurityContextFactory<WithTestActor> {
    @Override
    public SecurityContext createSecurityContext(WithTestActor fixture) {
        var capabilities = Set.of(fixture.capabilities());
        ErpActor actor = fixture.service()
                ? new ServiceActor("https://mulino-auth-test.example/", "test-worker@clients", "test-worker", capabilities)
                : new HumanActor("https://mulino-auth-test.example/", "auth0|domain-test", 1L,
                        "Domain fixture", fixture.role(), capabilities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthenticationToken(actor));
        return context;
    }
}
