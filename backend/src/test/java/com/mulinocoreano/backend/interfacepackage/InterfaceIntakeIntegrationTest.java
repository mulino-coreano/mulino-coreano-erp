package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InterfaceIntakeIntegrationTest {

    private static final String DEFAULT_CHANNEL_REF = "SYSTEM_DEFAULT";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InterfaceService service;

    @Test
    void bootstrapProvidesActiveOrchestratorAndDeterministicDefaultChannels() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM agents
                        WHERE agent_key='ORCHESTRATOR' AND is_active=true
                        """).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM channels
                        WHERE external_ref=:externalRef
                        """).param("externalRef", DEFAULT_CHANNEL_REF).query(Long.class).single())
                .isEqualTo(5);
    }

    @Test
    void createCaseAssignsActiveOrchestratorAndDefaultChatOrigin() throws Exception {
        String objective = "Intake objective " + shortId();

        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objective":"%s"}
                                """.formatted(objective)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intentType").value("ACT"));

        IntakeState state = jdbc.sql("""
                        SELECT c.case_ref, c.intent_type::text, a.agent_key, a.is_active,
                               ch.channel_type::text, ch.external_ref, wi.work_item_ref
                        FROM cases c
                        JOIN work_items wi ON wi.case_id=c.case_id
                        JOIN agents a ON a.agent_id=wi.assigned_agent_id
                        JOIN channels ch ON ch.channel_id=c.origin_channel_id
                        WHERE c.objective=:objective
                        """)
                .param("objective", objective)
                .query((rs, rowNum) -> new IntakeState(
                        rs.getString("case_ref"), rs.getString("intent_type"),
                        rs.getString("agent_key"), rs.getBoolean("is_active"),
                        rs.getString("channel_type"), rs.getString("external_ref"),
                        rs.getString("work_item_ref")))
                .single();

        assertThat(state.caseRef()).matches("CASE-[0-9a-f]{14}").hasSizeLessThanOrEqualTo(20);
        assertThat(state.workItemRef()).matches("WI-[0-9a-f]{16}").hasSizeLessThanOrEqualTo(20);
        assertThat(state.intentType()).isEqualTo("ACT");
        assertThat(state.agentKey()).isEqualTo("ORCHESTRATOR");
        assertThat(state.agentActive()).isTrue();
        assertThat(state.channelType()).isEqualTo("CHAT");
        assertThat(state.channelExternalRef()).isEqualTo(DEFAULT_CHANNEL_REF);
    }

    @Test
    void publicReferencesDoNotDependOnCurrentTableCardinality() {
        ensureActiveOrchestratorAndDefaultChat();
        long currentCases = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();
        String collidingCaseRef = "CASE-" + (1900 + currentCases + 1);
        long poisonCaseId = jdbc.sql("""
                        INSERT INTO cases (case_ref, title, objective, intent_type)
                        VALUES (:caseRef, 'Reference poison', 'Catch count-derived references', 'ACT')
                        RETURNING case_id
                        """)
                .param("caseRef", collidingCaseRef)
                .query(Long.class)
                .single();
        long currentWorkItems = jdbc.sql("SELECT count(*) FROM work_items").query(Long.class).single();
        String collidingWorkItemRef = "WI-" + (1900 + currentWorkItems + 1);
        jdbc.sql("""
                        INSERT INTO work_items (work_item_ref, case_id, title, status)
                        VALUES (:workItemRef, :caseId, 'Reference poison', 'READY')
                        """)
                .param("workItemRef", collidingWorkItemRef)
                .param("caseId", poisonCaseId)
                .update();

        CaseDto created = service.createCase(new CreateCaseRequest(
                "Create despite misleading cardinality", "ACT", "CHAT"));
        String createdWorkItemRef = jdbc.sql("""
                        SELECT work_item_ref FROM work_items WHERE case_id=:caseId
                        """)
                .param("caseId", created.caseId())
                .query(String.class)
                .single();

        assertThat(created.caseRef()).isNotEqualTo(collidingCaseRef);
        assertThat(createdWorkItemRef).isNotEqualTo(collidingWorkItemRef);
    }

    @Test
    void createCaseRejectsNonActIntentWithoutPersistingAnything() throws Exception {
        ensureActiveOrchestratorAndDefaultChat();
        long before = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();

        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"objective":"This is only a query","intentType":"ASK"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void createCaseRejectsBlankObjectiveAndUnknownChannelAsBadRequests() throws Exception {
        ensureActiveOrchestratorAndDefaultChat();

        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"Valid objective\",\"channel\":\"TEAMS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCaseFailsAtomicallyWhenOrchestratorIsInactive() throws Exception {
        ensureActiveOrchestratorAndDefaultChat();
        jdbc.sql("UPDATE agents SET is_active=false WHERE agent_key='ORCHESTRATOR'").update();
        long before = jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single();

        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objective\":\"Must have an active owner\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.sql("SELECT count(*) FROM cases").query(Long.class).single())
                .isEqualTo(before);
    }

    private void ensureActiveOrchestratorAndDefaultChat() {
        jdbc.sql("""
                        INSERT INTO agents (agent_key, display_name, is_active)
                        VALUES ('ORCHESTRATOR', 'Test Orchestrator', true)
                        ON CONFLICT (agent_key) DO UPDATE SET is_active=true
                        """).update();
        jdbc.sql("""
                        INSERT INTO channels (channel_type, external_ref, display_name)
                        SELECT 'CHAT', :externalRef, 'Test default chat'
                        WHERE NOT EXISTS (
                          SELECT 1 FROM channels
                          WHERE channel_type='CHAT' AND external_ref=:externalRef
                        )
                        """).param("externalRef", DEFAULT_CHANNEL_REF).update();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record IntakeState(String caseRef, String intentType, String agentKey,
                               boolean agentActive, String channelType,
                               String channelExternalRef, String workItemRef) {
    }
}
