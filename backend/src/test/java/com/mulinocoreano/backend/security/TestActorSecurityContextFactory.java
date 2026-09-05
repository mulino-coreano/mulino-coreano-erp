package com.mulinocoreano.backend.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

public class TestActorSecurityContextFactory implements WithSecurityContextFactory<WithTestActor> {
    @Autowired JdbcClient jdbc;
    @Override
    public SecurityContext createSecurityContext(WithTestActor fixture) {
        var capabilities = Set.of(fixture.capabilities());
        long userId=fixture.service()?0:jdbc.sql("INSERT INTO users(name,email,role) VALUES ('Domain fixture',:email,:role::user_role) RETURNING user_id")
                .param("email",UUID.randomUUID()+"@domain.example.test").param("role",fixture.role()).query(Long.class).single();
        ErpActor actor = fixture.service()
                ? new ServiceActor("https://mulino-auth-test.example/", "test-worker@clients", "test-worker", capabilities)
                : new HumanActor("https://mulino-auth-test.example/", "auth0|domain-test", userId,
                        "Domain fixture", fixture.role(), capabilities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthenticationToken(actor));
        return context;
    }
}
