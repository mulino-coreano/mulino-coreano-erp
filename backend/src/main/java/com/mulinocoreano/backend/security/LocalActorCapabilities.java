package com.mulinocoreano.backend.security;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 로컬 스텁용. Auth0 scope가 모두 있다고 가정한 역할→capability 매핑. */
final class LocalActorCapabilities {
    private LocalActorCapabilities() {}

    static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "MANAGER";
        }
        return role.trim().toUpperCase(Locale.ROOT);
    }

    static Set<String> forRole(String role) {
        String normalized = normalizeRole(role);
        Set<String> capabilities = new HashSet<>();
        capabilities.add("erp:read");
        switch (normalized) {
            case "ADMIN", "MANAGER" -> {
                capabilities.add("work:write");
                capabilities.add("procurement:decide");
            }
            case "OPERATOR", "QC" -> capabilities.add("work:write");
            case "VIEWER" -> {}
            default -> throw new IllegalArgumentException("Unsupported local role: " + normalized);
        }
        return Set.copyOf(capabilities);
    }
}
