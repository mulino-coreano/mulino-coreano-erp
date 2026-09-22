package com.mulinocoreano.backend.security;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalActorDirectory {
    private final JdbcClient jdbc;
    private final LocalAuthProperties properties;

    public LocalActorDirectory(JdbcClient jdbc, LocalAuthProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public HumanActor humanForRole(String role) {
        String normalized = LocalActorCapabilities.normalizeRole(role);
        var capabilities = LocalActorCapabilities.forRole(normalized);
        String email = "local-" + normalized.toLowerCase() + "@mulino.local";
        long userId =
                jdbc.sql(
                                """
                                INSERT INTO users(name, email, password, role)
                                VALUES (:name, :email, 'local-stub-only', CAST(:role AS user_role))
                                ON CONFLICT (email) DO UPDATE
                                  SET name = EXCLUDED.name,
                                      role = EXCLUDED.role,
                                      is_active = TRUE
                                RETURNING user_id
                                """)
                        .param("name", "Local " + normalized)
                        .param("email", email)
                        .param("role", normalized)
                        .query(Long.class)
                        .single();
        return new HumanActor(
                properties.issuer(),
                "local|" + normalized.toLowerCase(),
                userId,
                "Local " + normalized,
                normalized,
                capabilities);
    }
}
