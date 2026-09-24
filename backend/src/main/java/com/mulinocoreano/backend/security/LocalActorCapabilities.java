package com.mulinocoreano.backend.security;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** PoC 로컬 신원의 역할→capability 매핑. */
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
        // 제거한 Auth0 경로와 같은 규칙: 업무 쓰기는 OPERATOR·MANAGER, 구매 결정은 MANAGER만.
        switch (normalized) {
            case "MANAGER" -> {
                capabilities.add("work:write");
                capabilities.add("procurement:decide");
            }
            case "OPERATOR" -> capabilities.add("work:write");
            case "ADMIN", "QC", "VIEWER" -> {}
            default -> throw new IllegalArgumentException("Unsupported local role: " + normalized);
        }
        return Set.copyOf(capabilities);
    }
}
