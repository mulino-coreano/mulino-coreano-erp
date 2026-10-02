package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SchemaReviewIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void caseRejectsDuplicateAgentParticipant() {
        Fixture fixture = fixture();
        insertAgentParticipant(fixture.caseId(), fixture.agentId());

        assertThatThrownBy(() -> insertAgentParticipant(fixture.caseId(), fixture.agentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void caseRejectsDuplicateUserParticipant() {
        Fixture fixture = fixture();
        insertUserParticipant(fixture.caseId(), fixture.userId());

        assertThatThrownBy(() -> insertUserParticipant(fixture.caseId(), fixture.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionRejectsWorkItemFromAnotherCase() {
        Fixture decisionCase = fixture();
        Fixture workItemCase = fixture();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO decisions
                    (case_id, work_item_id, decision_text, decided_by_user_id)
                VALUES (:caseId, :workItemId, 'Cross-case decision', :userId)
                """)
                .param("caseId", decisionCase.caseId())
                .param("workItemId", workItemCase.workItemId())
                .param("userId", decisionCase.userId())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attentionRejectsWorkItemFromAnotherCase() {
        Fixture attentionCase = fixture();
        Fixture workItemCase = fixture();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO attention_requests
                    (case_id, work_item_id, reason_type, title, question)
                VALUES
                    (:caseId, :workItemId, 'MATERIAL_EXCEPTION',
                     'Cross-case attention', 'Which Case owns this Work Item?')
                """)
                .param("caseId", attentionCase.caseId())
                .param("workItemId", workItemCase.workItemId())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertAgentParticipant(long caseId, long agentId) {
        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, agent_id)
                VALUES (:caseId, 'AGENT', :agentId)
                """)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .update();
    }

    private void insertUserParticipant(long caseId, long userId) {
        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, user_id)
                VALUES (:caseId, 'USER', :userId)
                """)
                .param("caseId", caseId)
                .param("userId", userId)
                .update();
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:agentKey, 'Schema review agent')
                RETURNING agent_id
                """)
                .param("agentKey", "AGENT-" + suffix)
                .query(Long.class)
                .single();
        long userId = jdbc.sql("""
                INSERT INTO users (name, email, password, role)
                VALUES ('Schema Review User', :email, 'test-only', 'MANAGER')
                RETURNING user_id
                """)
                .param("email", "schema-" + suffix + "@example.test")
                .query(Long.class)
                .single();
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:caseRef, 'Schema review case', 'Keep Case scope coherent', 'ACT')
                RETURNING case_id
                """)
                .param("caseRef", "CASE-" + suffix)
                .query(Long.class)
                .single();
        long workItemId = jdbc.sql("""
                INSERT INTO work_items (work_item_ref, case_id, title, assigned_agent_id)
                VALUES (:workItemRef, :caseId, 'Schema review work', :agentId)
                RETURNING work_item_id
                """)
                .param("workItemRef", "WI-" + suffix)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        return new Fixture(caseId, workItemId, agentId, userId);
    }

    private record Fixture(long caseId, long workItemId, long agentId, long userId) {
    }
}
