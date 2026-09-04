package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunSchedulingIntegrationTest {

    @Autowired
    RunService runService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Validator validator;

    @Test
    void atomicSchedulingReturnsEmptyWhenWorkItemAlreadyHasRunningRun() {
        Fixture fixture = fixture();
        CreateRunRequest request = new CreateRunRequest(
                fixture.agentKey(), fixture.caseRef(), fixture.workItemRef(), "CODEX");
        long firstEventId = event(fixture, "first-" + shortId());
        long secondEventId = event(fixture, "second-" + shortId());

        RunDto first = runService.createRun(request, firstEventId);
        Optional<RunDto> duplicate = runService.tryCreateRun(request, secondEventId);

        assertThat(first.status()).isEqualTo("RUNNING");
        assertThat(duplicate).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:workItemId AND status='RUNNING'")
                .param("workItemId", fixture.workItemId()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void publicRunCreationReportsAnActiveRunConflictAtomically() {
        Fixture fixture = fixture();
        CreateRunRequest request = new CreateRunRequest(
                fixture.agentKey(), fixture.caseRef(), fixture.workItemRef(), "CODEX");

        runService.createRun(request, null);

        assertThatThrownBy(() -> runService.createRun(request, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("active RUNNING Run already exists");
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:workItemId AND status='RUNNING'")
                .param("workItemId", fixture.workItemId()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void createRunRejectsWorkItemFromAnotherCaseBeforeInsert() {
        Fixture requestedCase = fixture();
        Fixture otherCase = fixture();
        CreateRunRequest request = new CreateRunRequest(
                requestedCase.agentKey(), requestedCase.caseRef(), otherCase.workItemRef(), "CODEX");

        assertThatThrownBy(() -> runService.createRun(request, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("workItemRef does not belong to caseRef");
        assertThat(jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:workItemId")
                .param("workItemId", otherCase.workItemId()).query(Long.class).single())
                .isZero();
    }

    @Test
    void createRunRejectsWorkItemThatIsNotReadyBeforeInsert() {
        Fixture fixture = fixture();
        jdbc.sql("UPDATE work_items SET status='WAITING' WHERE work_item_id=:workItemId")
                .param("workItemId", fixture.workItemId())
                .update();

        assertRejectedBeforeInsert(fixture, fixture.agentKey(), "READY");
    }

    @Test
    void createRunRejectsWorkItemWithoutAnAgentAssignmentBeforeInsert() {
        Fixture fixture = fixture();
        jdbc.sql("UPDATE work_items SET assigned_agent_id=NULL WHERE work_item_id=:workItemId")
                .param("workItemId", fixture.workItemId())
                .update();

        assertRejectedBeforeInsert(fixture, fixture.agentKey(), "assigned agent");
    }

    @Test
    void createRunRejectsWorkItemAssignedToAUserBeforeInsert() {
        Fixture fixture = fixture();
        long userId = user();
        jdbc.sql("""
                UPDATE work_items
                SET assigned_agent_id=NULL, assigned_user_id=:userId
                WHERE work_item_id=:workItemId
                """)
                .param("userId", userId)
                .param("workItemId", fixture.workItemId())
                .update();

        assertRejectedBeforeInsert(fixture, fixture.agentKey(), "assigned user");
    }

    @Test
    void createRunRejectsRequestedAgentThatDoesNotMatchLockedAssignment() {
        Fixture fixture = fixture();
        String otherAgentKey = agent("Different active agent");

        assertRejectedBeforeInsert(fixture, otherAgentKey, "does not match");
    }

    @Test
    void createRunRejectsInactiveAssignedAgentBeforeInsert() {
        Fixture fixture = fixture();
        jdbc.sql("""
                UPDATE agents SET is_active=false
                WHERE agent_key=:agentKey
                """)
                .param("agentKey", fixture.agentKey())
                .update();

        assertRejectedBeforeInsert(fixture, fixture.agentKey(), "active");
    }

    @Test
    void caseOnlyRunRejectsInactiveRequestedAgentBeforeInsert() {
        Fixture fixture = fixture();
        jdbc.sql("""
                UPDATE agents SET is_active=false
                WHERE agent_key=:agentKey
                """)
                .param("agentKey", fixture.agentKey())
                .update();
        CreateRunRequest request = new CreateRunRequest(
                fixture.agentKey(), fixture.caseRef(), null, "CODEX");

        assertThatThrownBy(() -> runService.createRun(request, null))
                .isExactlyInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("active");
        assertThat(runCountForCase(fixture.caseId())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBeanValidationRequests")
    void createRunRequestDeclaresHttpBoundaryConstraints(
            String description, CreateRunRequest request, String property) {
        Set<ConstraintViolation<CreateRunRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(property);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedInternalRequests")
    void directCreateRunRejectsMalformedValuesBeforeDatabaseLookup(
            String description, CreateRunRequest request, String expectedMessage) {
        assertThatThrownBy(() -> runService.createRun(request, null))
                .isExactlyInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void postRunsRejectsUnsupportedRuntimeBeforeInsert() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentKey":"%s",
                                  "caseRef":"%s",
                                  "workItemRef":"%s",
                                  "runtime":"LOCAL"
                                }
                                """.formatted(
                                        fixture.agentKey(), fixture.caseRef(), fixture.workItemRef())))
                .andExpect(status().isBadRequest());

        assertThat(runCountForWorkItem(fixture.workItemId())).isZero();
    }

    private static Stream<Arguments> invalidBeanValidationRequests() {
        return Stream.of(
                Arguments.of("blank agent key",
                        new CreateRunRequest(" ", "CASE-VALID", null, "CODEX"), "agentKey"),
                Arguments.of("overlong agent key",
                        new CreateRunRequest("A".repeat(51), "CASE-VALID", null, "CODEX"), "agentKey"),
                Arguments.of("blank case reference",
                        new CreateRunRequest("AGENT-VALID", " ", null, "CODEX"), "caseRef"),
                Arguments.of("overlong case reference",
                        new CreateRunRequest("AGENT-VALID", "C".repeat(21), null, "CODEX"), "caseRef"),
                Arguments.of("overlong work item reference",
                        new CreateRunRequest("AGENT-VALID", "CASE-VALID", "W".repeat(21), "CODEX"),
                        "workItemRef"),
                Arguments.of("missing runtime",
                        new CreateRunRequest("AGENT-VALID", "CASE-VALID", null, null), "runtime"),
                Arguments.of("unsupported runtime",
                        new CreateRunRequest("AGENT-VALID", "CASE-VALID", null, "codex"), "runtime")
        );
    }

    private static Stream<Arguments> malformedInternalRequests() {
        return Stream.of(
                Arguments.of("overlong agent key",
                        new CreateRunRequest("A".repeat(51), "CASE-MISSING", null, "CODEX"),
                        "agentKey must be at most 50"),
                Arguments.of("overlong case reference before unknown agent lookup",
                        new CreateRunRequest("AGENT-MISSING", "C".repeat(21), null, "CODEX"),
                        "caseRef must be at most 20"),
                Arguments.of("overlong work item reference before unknown agent lookup",
                        new CreateRunRequest("AGENT-MISSING", "CASE-MISSING", "W".repeat(21), "CODEX"),
                        "workItemRef must be at most 20"),
                Arguments.of("unsupported runtime before unknown agent lookup",
                        new CreateRunRequest("AGENT-MISSING", "CASE-MISSING", null, "LOCAL"),
                        "runtime must be CLAUDE or CODEX")
        );
    }

    private Fixture fixture() {
        String agentKey = agent("Atomic scheduling agent");
        long agentId = jdbc.sql("SELECT agent_id FROM agents WHERE agent_key=:key")
                .param("key", agentKey)
                .query(Long.class)
                .single();
        String caseRef = "CASE-" + shortId();
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:ref, 'Atomic scheduling case', 'Create one active run', 'ACT')
                RETURNING case_id
                """)
                .param("ref", caseRef)
                .query(Long.class)
                .single();
        String workItemRef = "WI-" + shortId();
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, metadata)
                VALUES
                    (:ref, :caseId, 'Atomic scheduling work', 'READY', :agentId,
                     '{"businessRef":{"type":"stock","ref":"STOCK-ATOMIC"}}'::jsonb)
                RETURNING work_item_id
                """)
                .param("ref", workItemRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        return new Fixture(agentKey, caseId, caseRef, workItemId, workItemRef);
    }

    private String agent(String displayName) {
        String agentKey = "AGENT-" + shortId();
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:key, :displayName) RETURNING agent_id
                """)
                .param("key", agentKey)
                .param("displayName", displayName)
                .query(Long.class)
                .single();
        assertThat(agentId).isPositive();
        return agentKey;
    }

    private long user() {
        return jdbc.sql("""
                INSERT INTO users (name, email, password, role)
                VALUES ('Run scheduling user', :email, 'test-only', 'MANAGER')
                RETURNING user_id
                """)
                .param("email", shortId() + "@example.test")
                .query(Long.class)
                .single();
    }

    private void assertRejectedBeforeInsert(
            Fixture fixture, String requestedAgentKey, String expectedMessage) {
        CreateRunRequest request = new CreateRunRequest(
                requestedAgentKey, fixture.caseRef(), fixture.workItemRef(), "CODEX");

        assertThatThrownBy(() -> runService.createRun(request, null))
                .isExactlyInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining(expectedMessage);
        assertThat(runCountForWorkItem(fixture.workItemId())).isZero();
    }

    private long runCountForWorkItem(long workItemId) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:workItemId")
                .param("workItemId", workItemId)
                .query(Long.class)
                .single();
    }

    private long runCountForCase(long caseId) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE case_id=:caseId")
                .param("caseId", caseId)
                .query(Long.class)
                .single();
    }

    private long event(Fixture fixture, String externalRef) {
        return jdbc.sql("""
                INSERT INTO events (event_type, external_ref, case_id, work_item_id, payload)
                VALUES ('WORK_ITEM_STATUS_CHANGED', :externalRef, :caseId, :workItemId, '{}'::jsonb)
                RETURNING event_id
                """)
                .param("externalRef", externalRef)
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .query(Long.class)
                .single();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(String agentKey, long caseId, String caseRef,
                           long workItemId, String workItemRef) {
    }
}
