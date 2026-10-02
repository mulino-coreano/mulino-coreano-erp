package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ContextSnapshotCompletenessIntegrationTest {

    @Autowired
    ContextSnapshotService contextSnapshotService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"dependent_wi_ref", "dependentWiRef"})
    void obligationIdentifiesOwnershipAndActiveWaitingDependencies(String dependencyKey) {
        Fixture fixture = fixture(dependencyKey);

        JsonNode snapshot = snapshot(fixture.caseRef());

        JsonNode obligation = findByText(
                snapshot.path("obligation"), "ref", fixture.workItemRef());
        assertThat(obligation.path("title").asString()).isEqualTo("Confirm supplier capacity");
        assertThat(obligation.path("description").asString())
                .isEqualTo("Confirm the quantity available for the launch order");
        assertThat(obligation.path("assignee").path("actor_type").asString()).isEqualTo("AGENT");
        assertThat(obligation.path("assignee").path("actor_ref").asString())
                .isEqualTo(fixture.agentKey());
        JsonNode waiting = findByText(
                obligation.path("waiting_conditions"), "ref", fixture.waitingRef());
        assertThat(waiting.path("type").asString()).isEqualTo("DEPENDENCY_DONE");
        assertThat(waiting.path("reason").asString()).isEqualTo("Wait for demand calculation");
        assertThat(waiting.path("payload").path(dependencyKey).asString())
                .isEqualTo(fixture.dependencyRef());
        assertThat(obligation.path("dependencies").get(0).asString())
                .isEqualTo(fixture.dependencyRef());
    }

    @Test
    void organizationalContextIncludesAgentAndHumanParticipantRoles() {
        Fixture fixture = fixture();

        JsonNode organizational = snapshot(fixture.caseRef()).path("organizational");

        JsonNode agent = findByText(organizational, "actor_ref", fixture.agentKey());
        assertThat(agent.path("actor_type").asString()).isEqualTo("AGENT");
        assertThat(agent.path("agent").asString()).isEqualTo(fixture.agentKey());
        assertThat(agent.path("name").asString()).isEqualTo("Procurement context agent");
        assertThat(agent.path("role").asString()).isEqualTo("Supplier owner");
        JsonNode user = findByText(
                organizational, "actor_ref", Long.toString(fixture.userId()));
        assertThat(user.path("actor_type").asString()).isEqualTo("USER");
        assertThat(user.path("name").asString()).isEqualTo("Context Manager");
        assertThat(user.path("role").asString()).isEqualTo("Launch approver");
    }

    @Test
    void epistemicContextPreservesClaimsEvidenceLinksAndScopedDecisions() {
        Fixture fixture = fixture();

        JsonNode epistemic = snapshot(fixture.caseRef()).path("epistemic");

        JsonNode evidence = findByText(
                epistemic.path("evidence"), "ref", fixture.evidenceRef());
        assertThat(evidence.path("source_type").asString()).isEqualTo("EMAIL");
        assertThat(evidence.path("title").asString()).isEqualTo("Supplier capacity reply");
        assertThat(evidence.path("observed_at").isString()).isTrue();
        JsonNode claim = findByText(
                epistemic.path("claims"), "subject_ref", "SUP-18.available_quantity");
        assertThat(claim.path("status").asString()).isEqualTo("CONFLICTED");
        assertThat(claim.path("claim_text").asString())
                .isEqualTo("Supplier can deliver 600 cases by launch");
        JsonNode link = findByText(
                claim.path("evidence"), "ref", fixture.evidenceRef());
        assertThat(link.path("relation").asString()).isEqualTo("REFUTES");
        JsonNode decision = findByText(
                epistemic.path("decisions"), "work_item_ref", fixture.workItemRef());
        assertThat(decision.path("scope").asString()).isEqualTo("THIS_CASE");
        assertThat(decision.path("decision_text").asString())
                .isEqualTo("Use launch allocation as the priority");
        assertThat(decision.path("decided_by").path("user_id").asLong())
                .isEqualTo(fixture.userId());
    }

    @Test
    void claimProvenanceDerivesRunAgentAndRetainsRunForExplicitActor() {
        Fixture fixture = fixture();
        String runRef = "RUN-" + UUID.randomUUID().toString().substring(0, 8);
        long runId = jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, status, finished_at)
                VALUES
                    (:runRef, :agentId, :caseId, :workItemId, 'CODEX', 'COMPLETED',
                     CURRENT_TIMESTAMP)
                RETURNING run_id
                """)
                .param("runRef", runRef)
                .param("agentId", fixture.agentId())
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO claims
                    (case_id, subject_type, subject_ref, claim_text, asserted_by_run_id)
                VALUES
                    (:caseId, 'RUN_PROVENANCE', 'RUN_ONLY',
                     'Claim created by a Run', :runId)
                """)
                .param("caseId", fixture.caseId())
                .param("runId", runId)
                .update();
        jdbc.sql("""
                INSERT INTO claims
                    (case_id, subject_type, subject_ref, claim_text,
                     asserted_by_user_id, asserted_by_run_id)
                VALUES
                    (:caseId, 'RUN_PROVENANCE', 'EXPLICIT_USER',
                     'Claim explicitly attributed to a user during a Run', :userId, :runId)
                """)
                .param("caseId", fixture.caseId())
                .param("userId", fixture.userId())
                .param("runId", runId)
                .update();

        JsonNode claims = snapshot(fixture.caseRef()).path("epistemic").path("claims");

        JsonNode runOnly = findByText(claims, "subject_ref", "RUN_ONLY");
        assertThat(runOnly.path("asserted_by").path("actor_type").asString())
                .isEqualTo("AGENT");
        assertThat(runOnly.path("asserted_by").path("actor_ref").asString())
                .isEqualTo(fixture.agentKey());
        assertThat(runOnly.path("asserted_by").path("run_ref").asString())
                .isEqualTo(runRef);

        JsonNode explicitUser = findByText(claims, "subject_ref", "EXPLICIT_USER");
        assertThat(explicitUser.path("asserted_by").path("actor_type").asString())
                .isEqualTo("USER");
        assertThat(explicitUser.path("asserted_by").path("actor_ref").asString())
                .isEqualTo(Long.toString(fixture.userId()));
        assertThat(explicitUser.path("asserted_by").path("run_ref").asString())
                .isEqualTo(runRef);
    }

    private JsonNode snapshot(String caseRef) {
        Map<String, Object> context = contextSnapshotService.build(caseRef);
        return objectMapper.valueToTree(context);
    }

    private JsonNode findByText(JsonNode array, String field, String value) {
        assertThat(array.isArray()).isTrue();
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asString())) {
                return item;
            }
        }
        throw new AssertionError("No item with " + field + "=" + value + " in " + array);
    }

    private Fixture fixture() {
        return fixture("dependent_wi_ref");
    }

    private Fixture fixture(String dependencyKey) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String agentKey = "AGENT-" + suffix;
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:agentKey, 'Procurement context agent')
                RETURNING agent_id
                """)
                .param("agentKey", agentKey)
                .query(Long.class)
                .single();
        long userId = jdbc.sql("""
                INSERT INTO users (name, email, password, role)
                VALUES ('Context Manager', :email, 'test-only', 'MANAGER')
                RETURNING user_id
                """)
                .param("email", "context-" + suffix + "@example.test")
                .query(Long.class)
                .single();
        String caseRef = "CASE-" + suffix;
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:caseRef, 'Context completeness', 'Protect the launch supply', 'ACT')
                RETURNING case_id
                """)
                .param("caseRef", caseRef)
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, agent_id, role)
                VALUES (:caseId, 'AGENT', :agentId, 'Supplier owner')
                """)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .update();
        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, user_id, role)
                VALUES (:caseId, 'USER', :userId, 'Launch approver')
                """)
                .param("caseId", caseId)
                .param("userId", userId)
                .update();

        String dependencyRef = "WI-D-" + suffix;
        jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, resolved_at)
                VALUES
                    (:workItemRef, :caseId, 'Calculate launch demand', 'DONE', :agentId,
                     CURRENT_TIMESTAMP)
                """)
                .param("workItemRef", dependencyRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .update();
        String workItemRef = "WI-W-" + suffix;
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, description, status, assigned_agent_id, priority)
                VALUES
                    (:workItemRef, :caseId, 'Confirm supplier capacity',
                     'Confirm the quantity available for the launch order',
                     'WAITING', :agentId, 'HIGH')
                RETURNING work_item_id
                """)
                .param("workItemRef", workItemRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        String waitingRef = "WAIT-" + suffix;
        jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref, work_item_id, condition_type, condition_payload, reason)
                VALUES
                    (:waitingRef, :workItemId, 'DEPENDENCY_DONE',
                     jsonb_build_object(:dependencyKey, :dependencyRef),
                     'Wait for demand calculation')
                """)
                .param("waitingRef", waitingRef)
                .param("workItemId", workItemId)
                .param("dependencyKey", dependencyKey)
                .param("dependencyRef", dependencyRef)
                .update();

        String evidenceRef = "EV-" + suffix;
        long evidenceId = jdbc.sql("""
                INSERT INTO evidence (evidence_ref, case_id, source_type, title, content_uri, content_hash)
                VALUES (:evidenceRef, :caseId, 'EMAIL', 'Supplier capacity reply',
                        'mail://supplier/reply', 'sha256-context-test')
                RETURNING evidence_id
                """)
                .param("evidenceRef", evidenceRef)
                .param("caseId", caseId)
                .query(Long.class)
                .single();
        long claimId = jdbc.sql("""
                INSERT INTO claims
                    (case_id, subject_type, subject_ref, claim_text, status,
                     asserted_by_agent_id, resolved_at)
                VALUES
                    (:caseId, 'SUPPLIER', 'SUP-18.available_quantity',
                     'Supplier can deliver 600 cases by launch', 'CONFLICTED', :agentId,
                     CURRENT_TIMESTAMP)
                RETURNING claim_id
                """)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO claim_evidence (claim_id, evidence_id, relation)
                VALUES (:claimId, :evidenceId, 'REFUTES')
                """)
                .param("claimId", claimId)
                .param("evidenceId", evidenceId)
                .update();
        jdbc.sql("""
                INSERT INTO decisions
                    (case_id, work_item_id, decision_text, scope, decided_by_user_id)
                VALUES
                    (:caseId, :workItemId, 'Use launch allocation as the priority',
                     'THIS_CASE', :userId)
                """)
                .param("caseId", caseId)
                .param("workItemId", workItemId)
                .param("userId", userId)
                .update();

        return new Fixture(
                caseId, caseRef, agentId, agentKey, userId, workItemId, workItemRef,
                dependencyRef, waitingRef, evidenceRef);
    }

    private record Fixture(
            long caseId,
            String caseRef,
            long agentId,
            String agentKey,
            long userId,
            long workItemId,
            String workItemRef,
            String dependencyRef,
            String waitingRef,
            String evidenceRef) {
    }
}
