package com.mulinocoreano.backend.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import java.util.Locale;
import java.util.Optional;

@Component
@Profile("local")
public class LocalActorDirectory {
    private final JdbcClient jdbc;
    private final LocalAuthProperties properties;

    public LocalActorDirectory(JdbcClient jdbc, LocalAuthProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 역할별 로컬 사용자가 없을 때만 만든다. 기존 행의 활성 여부와 역할은 요청으로 바꾸지 않으며,
     * 비활성화되었거나 저장된 역할이 헤더 역할과 다르면 빈 값을 반환한다.
     */
    public Optional<HumanActor> humanForRole(String role) {
        String normalized = LocalActorCapabilities.normalizeRole(role);
        var capabilities = LocalActorCapabilities.forRole(normalized);
        String email = "local-" + normalized.toLowerCase(Locale.ROOT) + "@mulino.local";
        jdbc.sql("""
                        INSERT INTO users(name, email, password, role)
                        VALUES (:name, :email, 'local-stub-only', CAST(:role AS user_role))
                        ON CONFLICT (email) DO NOTHING
                        """)
                .param("name", "Local " + normalized)
                .param("email", email)
                .param("role", normalized)
                .update();
        return jdbc.sql("""
                        SELECT user_id, name FROM users
                        WHERE email = :email AND is_active AND role = CAST(:role AS user_role)
                        """)
                .param("email", email)
                .param("role", normalized)
                .query((rs, n) -> new HumanActor(
                        properties.issuer(),
                        "local|" + normalized.toLowerCase(Locale.ROOT),
                        rs.getLong("user_id"),
                        rs.getString("name"),
                        normalized,
                        capabilities))
                .optional();
    }
}
