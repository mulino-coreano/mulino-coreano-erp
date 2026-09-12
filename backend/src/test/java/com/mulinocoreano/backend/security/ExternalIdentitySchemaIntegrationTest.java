package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ExternalIdentitySchemaIntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void externalLoginsHaveAnExplicitIdentityRelationAndNoRequiredLocalPassword() {
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='external_identities'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='users' AND column_name='password'")
                .query(String.class).single()).isEqualTo("YES");
    }
}
