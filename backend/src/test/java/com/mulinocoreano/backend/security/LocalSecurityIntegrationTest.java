package com.mulinocoreano.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Auth0를 대신하는 PoC 로컬 신원(역할 헤더·실행기 토큰)의 접근 규칙. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LocalSecurityIntegrationTest {
    static final String WORKER = "Bearer test-worker-token";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void requestsWithoutAnIdentityAreUnauthenticated() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleHeaderBecomesAHumanWithThatRolesCapabilities() throws Exception {
        mvc.perform(get("/api/v1/me").header(LocalActorFilter.ROLE_HEADER, "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorType").value("HUMAN"))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("erp:read", "work:write", "procurement:decide")));
    }

    @Test
    void unknownRoleIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/me").header(LocalActorFilter.ROLE_HEADER, "HACKER"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "QC", "ADMIN"})
    void onlyOperatorsAndManagersMayCreateCases(String role) throws Exception {
        long count = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();
        mvc.perform(createCase().header(LocalActorFilter.ROLE_HEADER, role)).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single()).isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "OPERATOR"})
    void authorisedHumansCanCreateCases(String role) throws Exception {
        mvc.perform(createCase().header(LocalActorFilter.ROLE_HEADER, role))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intentType").value("ACT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/events", "/api/v1/runs", "/api/v1/dispatch"})
    void humansCannotInvokePrivilegedDispatcherWrites(String path) throws Exception {
        mvc.perform(post(path).header(LocalActorFilter.ROLE_HEADER, "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void workerTokenIsAServiceThatCanDispatchButNotActAsAHuman() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", WORKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorType").value("SERVICE"))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
        mvc.perform(createCase().header("Authorization", WORKER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dispatch").header("Authorization", WORKER)).andExpect(status().isAccepted());
    }

    @Test
    void unknownWriteRoutesFailClosedForManagers() throws Exception {
        mvc.perform(post("/api/v1/unknown-write-route").header(LocalActorFilter.ROLE_HEADER, "MANAGER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder createCase() {
        return post("/api/v1/cases")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objective\":\"로컬 신원 목표 접수\"}");
    }
}
