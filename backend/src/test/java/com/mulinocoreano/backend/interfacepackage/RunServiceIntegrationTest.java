package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Transactional
class RunServiceIntegrationTest {

    @Autowired
    RunService runService;

    @Autowired
    InterfaceService interfaceService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createRunRecordsTriggerAndFreshContext() {
        Fixture fixture = fixture(
                "Investigate delayed purchase order",
                "{\"type\":\"purchase_order\",\"ref\":\"PO-104\"}");
        long eventId = event(fixture);

        RunDto run = runService.createRun(request(fixture), eventId);

        JsonNode snapshot = snapshot(run.runId());
        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(triggerEventId(run.runId())).isEqualTo(eventId);
        assertThat(snapshot.path("objective").asString())
                .isEqualTo("Investigate delayed purchase order");
        assertThat(snapshot.path("reconstructed_at").isString()).isTrue();
        assertThat(snapshot.path("stale").asBoolean()).isFalse();
        assertThat(snapshot.path("business").path("references").isArray()).isTrue();
        assertThat(snapshot.path("business").toString())
                .contains("purchase_order", "PO-104")
                .doesNotContain("pending");
    }

    @Test
    void createRunAlwaysReconstructsChangedCurrentState() {
        Fixture fixture = fixture(
                "Original objective",
                "{\"type\":\"supplier\",\"ref\":\"SUP-7\"}");
        RunDto first = runService.createRun(request(fixture), null);
        complete(first.runId());

        jdbc.sql("UPDATE cases SET objective='Current objective' WHERE case_id=:caseId")
                .param("caseId", fixture.caseId())
                .update();
        jdbc.sql("""
                UPDATE work_items
                SET metadata='{"businessRef":{"type":"production_lot","ref":"LOT-2026-09"}}'::jsonb
                WHERE work_item_id=:workItemId
                """)
                .param("workItemId", fixture.workItemId())
                .update();

        RunDto second = runService.createRun(request(fixture), null);

        JsonNode firstSnapshot = snapshot(first.runId());
        JsonNode secondSnapshot = snapshot(second.runId());
        assertThat(firstSnapshot.path("objective").asString()).isEqualTo("Original objective");
        assertThat(firstSnapshot.path("business").toString()).contains("SUP-7");
        assertThat(secondSnapshot.path("objective").asString()).isEqualTo("Current objective");
        assertThat(secondSnapshot.path("business").toString())
                .contains("production_lot", "LOT-2026-09")
                .doesNotContain("SUP-7", "pending");
        assertThat(secondSnapshot.path("stale").asBoolean()).isFalse();
    }

    @Test
    void reconstructionRetriesThenFallsBackToNewestFreshCaseSnapshot() {
        Fixture fixture = fixture(
                "Older same-work-item snapshot",
                "{\"type\":\"purchase_order\",\"ref\":\"PO-OLDER\"}");
        RunDto older = runService.createRun(request(fixture), null);
        complete(older.runId());

        WorkItem other = workItem(
                fixture.caseId(), fixture.agentId(),
                "{\"type\":\"supplier\",\"ref\":\"SUP-NEWER\"}");
        jdbc.sql("UPDATE cases SET objective='Newer different-work-item snapshot' WHERE case_id=:caseId")
                .param("caseId", fixture.caseId())
                .update();
        RunDto newer = runService.createRun(new CreateRunRequest(
                fixture.agentKey(), fixture.caseRef(), other.ref(), "CODEX"), null);
        complete(newer.runId());

        AtomicInteger attempts = new AtomicInteger();
        ContextSnapshotService failingBuilder = new ContextSnapshotService(jdbc, objectMapper) {
            @Override
            public Map<String, Object> build(String caseRef) {
                attempts.incrementAndGet();
                throw new IllegalStateException("forced reconstruction failure");
            }
        };
        RunService failingRunService = new RunService(jdbc, objectMapper, failingBuilder);

        RunDto failed = failingRunService.createRun(request(fixture), null);

        JsonNode staleSnapshot = snapshot(failed.runId());
        assertThat(attempts).hasValue(2);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(finished(failed.runId())).isTrue();
        assertThat(staleSnapshot.path("objective").asString())
                .isEqualTo("Newer different-work-item snapshot");
        assertThat(staleSnapshot.path("business").toString())
                .contains("SUP-NEWER");
        assertThat(staleSnapshot.path("stale").asBoolean()).isTrue();
        assertThat(staleSnapshot.path("reconstruction_error").path("attempts").asInt())
                .isEqualTo(2);

        jdbc.sql("UPDATE cases SET objective='Fresh after failure' WHERE case_id=:caseId")
                .param("caseId", fixture.caseId())
                .update();
        RunDto fresh = runService.createRun(request(fixture), null);

        assertThat(snapshot(fresh.runId()).path("objective").asString()).isEqualTo("Fresh after failure");
        assertThat(snapshot(fresh.runId()).path("stale").asBoolean()).isFalse();
    }

    @Test
    void fallbackUsesFreshSnapshotEvenWhenItsRunLaterFailed() {
        Fixture fixture = fixture(
                "Successful snapshot before failed row",
                "{\"type\":\"purchase_order\",\"ref\":\"PO-SUCCESS\"}");
        RunDto successful = runService.createRun(request(fixture), null);
        complete(successful.runId());
        insertFailedFreshRun(fixture, "Forbidden failed-but-fresh snapshot");

        RunDto failed = createFailedRun(fixture, "forced fallback selection");

        assertThat(snapshot(failed.runId()).path("objective").asString())
                .isEqualTo("Forbidden failed-but-fresh snapshot");
    }

    @Test
    void contextReconstructionReadsEveryDynamicLayerAndTimestampWithOneStatement() {
        Fixture fixture = fixture(
                "One statement objective",
                "{\"type\":\"stock\",\"ref\":\"STOCK-ONE-SELECT\"}");
        String evidenceRef = unique("EV");
        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, agent_id)
                VALUES (:caseId, 'AGENT', :agentId)
                """)
                .param("caseId", fixture.caseId())
                .param("agentId", fixture.agentId())
                .update();
        jdbc.sql("""
                INSERT INTO evidence (evidence_ref, case_id, source_type)
                VALUES (:evidenceRef, :caseId, 'MANUAL')
                """)
                .param("evidenceRef", evidenceRef)
                .param("caseId", fixture.caseId())
                .update();
        AtomicInteger statements = new AtomicInteger();
        ContextSnapshotService oneStatementService = new ContextSnapshotService(
                countingJdbc(statements), objectMapper);

        JsonNode snapshot = objectMapper.valueToTree(oneStatementService.build(fixture.caseRef()));

        assertThat(statements).hasValue(1);
        assertThat(snapshot.path("objective").asString()).isEqualTo("One statement objective");
        assertThat(snapshot.path("obligation").toString()).contains(fixture.workItemRef(), "READY");
        assertThat(snapshot.path("organizational").toString()).contains(fixture.agentKey());
        assertThat(snapshot.path("business").toString()).contains("STOCK-ONE-SELECT");
        assertThat(snapshot.path("epistemic").toString()).contains(evidenceRef);
        assertThat(snapshot.path("reconstructed_at").isString()).isTrue();
        assertThat(snapshot.path("stale").asBoolean()).isFalse();
    }

    @Test
    void fallbackExcludesStaleSnapshotEvenWhenRunDidNotFail() {
        Fixture fixture = fixture(
                "Successful snapshot before stale row",
                "{\"type\":\"stock\",\"ref\":\"STOCK-SUCCESS\"}");
        RunDto successful = runService.createRun(request(fixture), null);
        complete(successful.runId());
        insertCompletedStaleRun(fixture, "Forbidden nonfailed-but-stale snapshot");

        RunDto failed = createFailedRun(fixture, "forced fallback selection");

        assertThat(snapshot(failed.runId()).path("objective").asString())
                .isEqualTo("Successful snapshot before stale row");
        assertThat(snapshot(failed.runId()).toString())
                .doesNotContain("Forbidden nonfailed-but-stale snapshot");
    }

    @Test
    void fallbackExcludesLegacySnapshotWithoutExplicitFreshMarker() {
        Fixture fixture = fixture(
                "Successful explicitly fresh snapshot",
                "{\"type\":\"supplier\",\"ref\":\"SUP-SUCCESS\"}");
        RunDto successful = runService.createRun(request(fixture), null);
        complete(successful.runId());
        insertLegacyPendingRun(fixture, "Forbidden legacy pending snapshot");

        RunDto failed = createFailedRun(fixture, "forced fallback selection");

        JsonNode fallback = snapshot(failed.runId());
        assertThat(fallback.path("objective").asString())
                .isEqualTo("Successful explicitly fresh snapshot");
        assertThat(fallback.toString())
                .doesNotContain("Forbidden legacy pending snapshot", "erp_link", "pending");
    }

    @Test
    void reconstructionFailureWithoutPriorSnapshotPersistsMinimalStaleAuditContext() {
        Fixture fixture = fixture(
                "No prior snapshot",
                "{\"type\":\"stock\",\"ref\":\"STOCK-9\"}");
        AtomicInteger attempts = new AtomicInteger();
        ContextSnapshotService failingBuilder = new ContextSnapshotService(jdbc, objectMapper) {
            @Override
            public Map<String, Object> build(String caseRef) {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("context source unavailable");
            }
        };
        RunService failingRunService = new RunService(jdbc, objectMapper, failingBuilder);

        RunDto failed = failingRunService.createRun(request(fixture), null);

        JsonNode snapshot = snapshot(failed.runId());
        assertThat(attempts).hasValue(2);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(finished(failed.runId())).isTrue();
        assertThat(snapshot.path("stale").asBoolean()).isTrue();
        assertThat(snapshot.path("reconstructed_at").isString()).isTrue();
        assertThat(snapshot.path("reconstruction_error").path("type").asString())
                .isEqualTo("IllegalArgumentException");
        assertThat(snapshot.path("reconstruction_error").path("message").asString())
                .isEqualTo("context source unavailable");
        assertThat(snapshot.path("reconstruction_error").path("attempts").asInt())
                .isEqualTo(2);
    }

    @Test
    void reconstructionRetryRecoversFromDatabaseFailureInTheSameTransaction() {
        Fixture fixture = fixture(
                "Uncommitted dispatcher-visible objective",
                "{\"type\":\"purchase_order\",\"ref\":\"PO-RETRY\"}");
        AtomicInteger attempts = new AtomicInteger();
        ContextSnapshotService failsOnce = new ContextSnapshotService(jdbc, objectMapper) {
            @Override
            public Map<String, Object> build(String caseRef) {
                if (attempts.incrementAndGet() == 1) {
                    jdbc.sql("SELECT missing_reconstruction_column FROM cases")
                            .query(String.class)
                            .list();
                }
                return super.build(caseRef);
            }
        };
        RunService retryingRunService = new RunService(jdbc, objectMapper, failsOnce);
        AtomicReference<RunDto> result = new AtomicReference<>();

        assertThatCode(() -> result.set(retryingRunService.createRun(request(fixture), null)))
                .doesNotThrowAnyException();

        assertThat(attempts).hasValue(2);
        assertThat(result.get().status()).isEqualTo("RUNNING");
        assertThat(snapshot(result.get().runId()).path("objective").asString())
                .isEqualTo("Uncommitted dispatcher-visible objective");
        assertThat(snapshot(result.get().runId()).path("stale").asBoolean()).isFalse();
    }

    @Test
    void hasActiveRunDetectsOnlyRunningSchedulingRecords() {
        Fixture fixture = fixture(
                "Active scheduling guard",
                "{\"type\":\"stock\",\"ref\":\"STOCK-11\"}");

        assertThat(runService.hasActiveRun(fixture.workItemId())).isFalse();

        RunDto running = runService.createRun(request(fixture), null);
        assertThat(runService.hasActiveRun(fixture.workItemId())).isTrue();

        complete(running.runId());
        assertThat(runService.hasActiveRun(fixture.workItemId())).isFalse();
    }

    @Test
    void interfaceServiceCreateRunDelegatesToTransactionalRunService() {
        Fixture fixture = fixture(
                "Interface delegation",
                "{\"type\":\"stock\",\"ref\":\"STOCK-DELEGATE\"}");

        RunDto run = interfaceService.createRun(request(fixture));

        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(triggerEventIsNull(run.runId())).isTrue();
        assertThat(snapshot(run.runId()).path("objective").asString())
                .isEqualTo("Interface delegation");
        assertThat(snapshot(run.runId()).path("stale").asBoolean()).isFalse();
    }

    private Fixture fixture(String objective, String businessRef) {
        String agentKey = unique("AGENT");
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:agentKey, 'Run Service Test Agent')
                RETURNING agent_id
                """)
                .param("agentKey", agentKey)
                .query(Long.class)
                .single();
        String caseRef = unique("CASE");
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:caseRef, 'Run service integration test', :objective, 'ACT')
                RETURNING case_id
                """)
                .param("caseRef", caseRef)
                .param("objective", objective)
                .query(Long.class)
                .single();
        WorkItem workItem = workItem(caseId, agentId, businessRef);
        return new Fixture(caseId, caseRef, workItem.id(), workItem.ref(), agentId, agentKey);
    }

    private WorkItem workItem(long caseId, long agentId, String businessRef) {
        String workItemRef = unique("WI");
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, metadata)
                VALUES
                    (:workItemRef, :caseId, 'Run service test work', 'READY', :agentId,
                     jsonb_build_object('businessRef', CAST(:businessRef AS jsonb)))
                RETURNING work_item_id
                """)
                .param("workItemRef", workItemRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .param("businessRef", businessRef)
                .query(Long.class)
                .single();
        return new WorkItem(workItemId, workItemRef);
    }

    private long event(Fixture fixture) {
        return jdbc.sql("""
                INSERT INTO events (event_type, case_id, work_item_id, payload)
                VALUES ('SUPPLIER_EMAIL_RECEIVED', :caseId, :workItemId, '{}'::jsonb)
                RETURNING event_id
                """)
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .query(Long.class)
                .single();
    }

    private CreateRunRequest request(Fixture fixture) {
        return new CreateRunRequest(
                fixture.agentKey(), fixture.caseRef(), fixture.workItemRef(), "CODEX");
    }

    private JsonNode snapshot(long runId) {
        String json = jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single();
        return objectMapper.readTree(json);
    }

    private Long triggerEventId(long runId) {
        return jdbc.sql("SELECT trigger_event_id FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private boolean triggerEventIsNull(long runId) {
        return jdbc.sql("SELECT trigger_event_id IS NULL FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(Boolean.class)
                .single();
    }

    private boolean finished(long runId) {
        return jdbc.sql("SELECT finished_at IS NOT NULL FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(Boolean.class)
                .single();
    }

    private void complete(long runId) {
        jdbc.sql("""
                UPDATE runs SET status='COMPLETED', finished_at=CURRENT_TIMESTAMP WHERE run_id=:runId
                """)
                .param("runId", runId)
                .update();
    }

    private RunDto createFailedRun(Fixture fixture, String message) {
        ContextSnapshotService failingBuilder = new ContextSnapshotService(jdbc, objectMapper) {
            @Override
            public Map<String, Object> build(String caseRef) {
                throw new IllegalStateException(message);
            }
        };
        return new RunService(jdbc, objectMapper, failingBuilder)
                .createRun(request(fixture), null);
    }

    private void insertFailedFreshRun(Fixture fixture, String objective) {
        jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, context_snapshot,
                     status, finished_at)
                VALUES
                    (:runRef, :agentId, :caseId, :workItemId, 'CODEX',
                     jsonb_build_object(
                         'objective', :objective,
                         'reconstructed_at', CURRENT_TIMESTAMP::text,
                         'stale', false),
                     'FAILED', CURRENT_TIMESTAMP)
                """)
                .param("runRef", unique("RUN"))
                .param("agentId", fixture.agentId())
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .param("objective", objective)
                .update();
    }

    private void insertCompletedStaleRun(Fixture fixture, String objective) {
        jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, context_snapshot,
                     status, finished_at)
                VALUES
                    (:runRef, :agentId, :caseId, :workItemId, 'CODEX',
                     jsonb_build_object(
                         'objective', :objective,
                         'reconstructed_at', CURRENT_TIMESTAMP::text,
                         'stale', true),
                     'COMPLETED', CURRENT_TIMESTAMP)
                """)
                .param("runRef", unique("RUN"))
                .param("agentId", fixture.agentId())
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .param("objective", objective)
                .update();
    }

    private void insertLegacyPendingRun(Fixture fixture, String objective) {
        jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, context_snapshot,
                     status, finished_at)
                VALUES
                    (:runRef, :agentId, :caseId, :workItemId, 'CODEX',
                     jsonb_build_object(
                         'objective', :objective,
                         'business', jsonb_build_object('erp_link', 'pending'),
                         'reconstructed_at', CURRENT_TIMESTAMP::text),
                     'COMPLETED', CURRENT_TIMESTAMP)
                """)
                .param("runRef", unique("RUN"))
                .param("agentId", fixture.agentId())
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .param("objective", objective)
                .update();
    }

    private JdbcClient countingJdbc(AtomicInteger statements) {
        return (JdbcClient) Proxy.newProxyInstance(
                JdbcClient.class.getClassLoader(),
                new Class<?>[]{JdbcClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sql")) {
                        statements.incrementAndGet();
                    }
                    try {
                        return method.invoke(jdbc, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(long caseId, String caseRef, long workItemId, String workItemRef,
                           long agentId, String agentKey) {
    }

    private record WorkItem(long id, String ref) {
    }
}
