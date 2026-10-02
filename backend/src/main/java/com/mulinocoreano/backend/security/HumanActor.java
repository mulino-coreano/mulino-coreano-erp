package com.mulinocoreano.backend.security;

import java.util.Set;

public record HumanActor(String issuer, String subject, long userId, String name,
                         String role, Set<String> capabilities) implements ErpActor {
    public HumanActor { capabilities = Set.copyOf(capabilities); }
}
