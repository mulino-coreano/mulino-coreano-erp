package com.mulinocoreano.backend.security;

import com.mulinocoreano.backend.interfacepackage.CaseIntakeService;
import com.mulinocoreano.backend.interfacepackage.CreateCaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 목표 2·4: 인간 권한과 중복 없는 업무 접수. 실제 DB·보안 필터를 통과한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class LocalActorIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CaseIntakeService intake;

    @Test
    void onlyPermittedHumansCreateCasesAndReadsRemainOpen() throws Exception {
        long before = count();
        mvc.perform(post("/api/v1/cases").contentType(MediaType.APPLICATION_JSON).content(body("denied")))
                .andExpect(status().isUnauthorized());
        for (String role : new String[]{"VIEWER", "QC", "ADMIN"}) {
            mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role", role)
                    .contentType(MediaType.APPLICATION_JSON).content(body("denied"))).andExpect(status().isForbidden());
        }
        assertThat(count()).isEqualTo(before);
        for (String role : new String[]{"MANAGER", "OPERATOR"}) {
            var response = mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role", role)
                    .contentType(MediaType.APPLICATION_JSON).content(body("allowed")))
                    .andExpect(status().isOk()).andReturn();
            long id = mapper.readTree(response.getResponse().getContentAsString()).get("caseId").asLong();
            assertThat(jdbc.sql("SELECT u.role::text FROM cases c JOIN users u ON u.user_id=c.opened_by_user_id WHERE case_id=:id")
                    .param("id", id).query(String.class).single()).isEqualTo(role);
            assertThat(jdbc.sql("SELECT count(*) FROM case_participants WHERE case_id=:id AND actor_type='USER'")
                    .param("id",id).query(Long.class).single()).isEqualTo(1);
        }
        mvc.perform(get("/api/v1/cases")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cases").header("X-Mulino-Local-Role", "INVALID")
                .header("Authorization", "invalid")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("X-Mulino-Local-Role", "VIEWER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void sameHumanReplaysReceiptAndConflictingInputDoesNotCreateWork() throws Exception {
        String key = UUID.randomUUID().toString();
        long before = count();
        String first = create("MANAGER", key, "unique objective");
        assertThat(create("MANAGER", key, "unique objective")).isEqualTo(first);
        mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER")
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(body("different"))).andExpect(status().isConflict());
        assertThat(count()).isEqualTo(before + 1);
        assertThat(create("OPERATOR", key, "unique objective")).isNotEqualTo(first);
        assertThat(count()).isEqualTo(before + 2);
    }

    @Test
    void failedCreationLeavesNoReceiptAndCanBeRetried() throws Exception {
        String key = UUID.randomUUID().toString();
        jdbc.sql("UPDATE agents SET is_active=false WHERE agent_key='ORCHESTRATOR'").update();
        mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER")
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(body("retry"))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM request_idempotency WHERE request_key=:key")
                .param("key",key).query(Long.class).single()).isZero();
        jdbc.sql("UPDATE agents SET is_active=true WHERE agent_key='ORCHESTRATOR'").update();
        create("MANAGER", key, "retry");
    }

    @Test
    @WithTestActor(service=true, capabilities={"work:write"})
    void serviceCannotMasqueradeAsAnonymousOrHuman() {
        long before = count();
        assertThatThrownBy(() -> intake.createCase(new CreateCaseRequest("denied","ACT","CHAT"),"key"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(count()).isEqualTo(before);
    }

    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void otherAuthenticatedPrincipalCannotFallBackToAnonymous() {
        long before = count();
        assertThatThrownBy(() -> intake.createCase(new CreateCaseRequest("denied","ACT","CHAT"),"key"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(count()).isEqualTo(before);
    }

    @Test
    @WithTestActor(capabilities={"work:write"})
    void inactiveHumanCannotDelegateWork() {
        var actor = (HumanActor) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        jdbc.sql("UPDATE users SET is_active=false WHERE user_id=:id").param("id",actor.userId()).update();
        long before = count();
        assertThatThrownBy(() -> intake.createCase(new CreateCaseRequest("denied","ACT","CHAT"),"key"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void invalidCredentialsCannotFallBackToRole() throws Exception {
        long before = count();
        mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","MANAGER")
                .header("Authorization","Bearer invalid").contentType(MediaType.APPLICATION_JSON).content(body("denied")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role","unknown")
                .contentType(MediaType.APPLICATION_JSON).content(body("denied"))).andExpect(status().isBadRequest());
        assertThat(count()).isEqualTo(before);
    }
    private String create(String role, String key, String objective) throws Exception {
        return mvc.perform(post("/api/v1/cases").header("X-Mulino-Local-Role",role)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(objective)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private String body(String objective) { return "{\"objective\":\"" + objective + "\"}"; }
    private long count() { return jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single(); }
}
