package com.mulinocoreano.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DefaultProfileIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Test
    void anonymousIntakeIgnoresLocalIdentityAndIdempotencyHeaders() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM cases WHERE opened_by_user_id IS NULL").query(Long.class).single();
        for (int i=0; i<2; i++) {
            mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER")
                    .header("Idempotency-Key","same-key").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"objective\":\"anonymous case\"}")).andExpect(status().isOk());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM cases WHERE opened_by_user_id IS NULL").query(Long.class).single())
                .isEqualTo(before + 2);
    }
    @Test
    void existingDocsAndReadsStayOpenButIdentityAndUnknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").isNotEmpty());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").isNotEmpty());
        mvc.perform(get("/api/v1/cases")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me")).andExpect(status().isForbidden());
        mvc.perform(get("/unlisted")).andExpect(status().isForbidden());
    }
}
