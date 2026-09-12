package com.mulinocoreano.backend.security;

import static com.mulinocoreano.backend.generated.Tables.EXTERNAL_IDENTITIES;
import static com.mulinocoreano.backend.generated.Tables.USERS;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ExternalIdentityRepository {
    private final DSLContext dsl;

    public ExternalIdentityRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // 매 요청마다 조회하여 기존 토큰에도 역할 변경·비활성화를 즉시 반영한다.
    public Optional<RegisteredUser> findActiveUser(String issuer, String subject) {
        return dsl.select(USERS.USER_ID, USERS.NAME, USERS.ROLE)
                .from(EXTERNAL_IDENTITIES)
                .join(USERS)
                .on(USERS.USER_ID.eq(EXTERNAL_IDENTITIES.USER_ID))
                .where(EXTERNAL_IDENTITIES.ISSUER.eq(issuer))
                .and(EXTERNAL_IDENTITIES.SUBJECT.eq(subject))
                .and(USERS.IS_ACTIVE.isTrue())
                .fetchOptional(
                        row ->
                                new RegisteredUser(
                                        row.value1(), row.value2(), row.value3().getLiteral()));
    }

    public record RegisteredUser(long userId, String name, String role) {}
}
