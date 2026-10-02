package com.mulinocoreano.backend.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RequestIdempotencyIntegrationTest {
    @Autowired RequestIdempotency replay;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    final String scope = "idempotency-test:" + UUID.randomUUID();
    final String emailPrefix = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM request_idempotency WHERE scope=:scope").param("scope", scope).update();
        jdbc.sql("DELETE FROM users WHERE email LIKE :prefix").param("prefix", emailPrefix + "%").update();
    }

    @Test @Transactional
    void sameCanonicalRequestReturnsOriginalReceiptWithOnlyOneEffect() {
        var first = replay.execute(scope, "one", Map.of("quantity", new BigDecimal("1.00"), "name", "demo"), this::effect);
        var second = replay.execute(scope, "one", Map.of("name", "demo", "quantity", BigDecimal.ONE), this::effect);
        assertThat(second).isEqualTo(first);
        assertThat(countEffects()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE scope=:scope").param("scope", scope).query(Long.class).single()).isEqualTo(1);
    }

    @Test @Transactional
    void conflictingRequestCannotExecuteAnotherEffect() {
        replay.execute(scope, "one", Map.of("quantity", 1), this::effect);
        assertThatThrownBy(() -> replay.execute(scope, "one", Map.of("quantity", 2), this::effect))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        assertThat(countEffects()).isEqualTo(1);
    }

    @Test
    void failedTransactionLeavesNeitherBusinessEffectNorReplayReceipt() {
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> replay.execute(scope, "fail", Map.of("quantity", 1), () -> {
            effect();
            throw new IllegalStateException("simulated failure");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(countEffects()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE scope=:scope").param("scope", scope).query(Long.class).single()).isZero();
    }

    @Test
    void refusesToRunWithoutAnAtomicCallerTransaction() {
        assertThatThrownBy(() -> replay.execute(scope, "unsafe", Map.of(), this::effect))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThat(countEffects()).isZero();
    }

    @Test @Transactional
    void missingOrOversizedKeysFailBeforeMutation() {
        for (String key : new String[]{"", " ", "a".repeat(201)}) {
            assertThatThrownBy(() -> replay.execute(scope, key, Map.of(), this::effect)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(countEffects()).isZero();
    }

    private Map<String,Object> effect() {
        long id = jdbc.sql("INSERT INTO users(name,email,role) VALUES ('Replay test',:email,'VIEWER') RETURNING user_id")
                .param("email", emailPrefix + UUID.randomUUID() + "@example.test").query(Long.class).single();
        return Map.of("userId", id, "quantity", new BigDecimal("999999999999.999999"));
    }
    private long countEffects() {
        return jdbc.sql("SELECT count(*) FROM users WHERE email LIKE :prefix").param("prefix", emailPrefix + "%").query(Long.class).single();
    }
}
