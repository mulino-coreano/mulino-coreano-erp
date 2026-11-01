package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 목표 2: 인간 secret이 설정되지 않은 local 백엔드는 어떤 역할 헤더도 인간으로 인정하지 않는다. */
@SpringBootTest(properties = "mulino.local-auth.human-secret=")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class LocalHumanSecretRequiredIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void unsetHumanSecretFailsClosedForEveryRoleHeader() throws Exception {
        long cases = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();
        for (String secret : new String[]{null, "", LocalHumanRequest.SECRET}) {
            var me = get("/api/v1/me").header(LocalActorFilter.ROLE_HEADER, "MANAGER");
            var intake = post("/api/v1/cases").header(LocalActorFilter.ROLE_HEADER, "MANAGER")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"objective\":\"unconfigured\"}");
            if (secret != null) {
                me.header(LocalActorFilter.HUMAN_SECRET_HEADER, secret);
                intake.header(LocalActorFilter.HUMAN_SECRET_HEADER, secret);
            }
            mvc.perform(me).andExpect(status().isUnauthorized());
            mvc.perform(intake).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single()).isEqualTo(cases);
    }
}
