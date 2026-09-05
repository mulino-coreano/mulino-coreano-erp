package com.mulinocoreano.backend.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ExternalIdentityRepository {
    private final JdbcClient jdbc;

    public ExternalIdentityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // 매 요청마다 조회하여 기존 토큰에도 역할 변경·비활성화를 즉시 반영한다.
    public Optional<RegisteredUser> findActiveUser(String issuer, String subject) {
        return jdbc.sql("""
                SELECT u.user_id, u.name, u.role::text
                FROM external_identities i
                JOIN users u ON u.user_id=i.user_id
                WHERE i.issuer=:issuer AND i.subject=:subject AND u.is_active=true
                """).param("issuer", issuer).param("subject", subject)
                .query((rs, row) -> new RegisteredUser(rs.getLong("user_id"), rs.getString("name"), rs.getString("role")))
                .optional();
    }

    public record RegisteredUser(long userId, String name, String role) {}
}
