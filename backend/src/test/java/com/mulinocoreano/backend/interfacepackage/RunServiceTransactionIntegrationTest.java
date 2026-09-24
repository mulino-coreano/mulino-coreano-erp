package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RunServiceTransactionIntegrationTest {

    @Autowired
    RunService runService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    FailsOnceContextSnapshotService contextSnapshotService;

    @Test
    void proxiedCreateRunRecoversAtSavepointAndCommits() {
        Fixture fixture = fixture();
        contextSnapshotService.reset();

        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            RunDto run = runService.createRun(new CreateRunRequest(
                    fixture.agentKey(), fixture.caseRef(), fixture.workItemRef(), "CODEX"), null);

            assertThat(contextSnapshotService.attempts()).isEqualTo(2);
            assertThat(contextSnapshotService.observedTransaction()).isTrue();
            assertThat(run.status()).isEqualTo("RUNNING");
            assertThat(run.runRef()).startsWith("RUN-");
            assertThat(persistedStatus(run.runId())).isEqualTo("RUNNING");
            assertThat(snapshot(run.runId()).path("objective").asString())
                    .isEqualTo("Committed run after savepoint recovery");
            assertThat(snapshot(run.runId()).path("stale").asBoolean()).isFalse();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void workItemAssignmentRemainsLockedUntilRunCreationCommits() throws Exception {
        Fixture fixture = fixture();
        contextSnapshotService.blockNextBuild();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<RunDto> creation = null;
        Future<Integer> reassignment = null;

        try {
            creation = workers.submit(() -> runService.createRun(new CreateRunRequest(
                    fixture.agentKey(), fixture.caseRef(), fixture.workItemRef(), "CODEX"), null));
            assertThat(contextSnapshotService.awaitBlockedBuild()).isTrue();

            reassignment = workers.submit(() -> jdbc.sql("""
                    UPDATE work_items SET assigned_agent_id=NULL
                    WHERE work_item_id=:workItemId
                    """)
                    .param("workItemId", fixture.workItemId())
                    .update());
            Future<Integer> pendingReassignment = reassignment;

            assertThatThrownBy(() -> pendingReassignment.get(300, TimeUnit.MILLISECONDS))
                    .isExactlyInstanceOf(TimeoutException.class);

            contextSnapshotService.releaseBlockedBuild();
            RunDto run = creation.get(5, TimeUnit.SECONDS);
            assertThat(persistedAgentId(run.runId())).isEqualTo(fixture.agentId());
            assertThat(reassignment.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            contextSnapshotService.releaseBlockedBuild();
            awaitCompletion(creation);
            awaitCompletion(reassignment);
            workers.shutdownNow();
            deleteFixture(fixture);
        }
    }

    private Fixture fixture() {
        String agentKey = unique("AGENT");
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:agentKey, 'Transaction Boundary Test Agent')
                RETURNING agent_id
                """)
                .param("agentKey", agentKey)
                .query(Long.class)
                .single();
        String caseRef = unique("CASE");
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:caseRef, 'Transaction boundary test',
                        'Committed run after savepoint recovery', 'ACT')
                RETURNING case_id
                """)
                .param("caseRef", caseRef)
                .query(Long.class)
                .single();
        String workItemRef = unique("WI");
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, metadata)
                VALUES
                    (:workItemRef, :caseId, 'Transaction boundary work', 'READY', :agentId,
                     '{"businessRef":{"type":"stock","ref":"STOCK-TX"}}'::jsonb)
                RETURNING work_item_id
                """)
                .param("workItemRef", workItemRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        return new Fixture(agentId, agentKey, caseId, caseRef, workItemId, workItemRef);
    }

    private String persistedStatus(long runId) {
        return jdbc.sql("SELECT status::text FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single();
    }

    private long persistedAgentId(long runId) {
        return jdbc.sql("SELECT agent_id FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(Long.class)
                .single();
    }

    private void awaitCompletion(Future<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Preserve the test's primary assertion failure while ensuring DB work has stopped.
        }
    }

    private JsonNode snapshot(long runId) {
        String json = jdbc.sql("SELECT context_snapshot::text FROM runs WHERE run_id=:runId")
                .param("runId", runId)
                .query(String.class)
                .single();
        return objectMapper.readTree(json);
    }

    private void deleteFixture(Fixture fixture) {
        jdbc.sql("DELETE FROM runs WHERE case_id=:caseId")
                .param("caseId", fixture.caseId())
                .update();
        jdbc.sql("DELETE FROM work_items WHERE work_item_id=:workItemId")
                .param("workItemId", fixture.workItemId())
                .update();
        jdbc.sql("DELETE FROM cases WHERE case_id=:caseId")
                .param("caseId", fixture.caseId())
                .update();
        jdbc.sql("DELETE FROM agents WHERE agent_id=:agentId")
                .param("agentId", fixture.agentId())
                .update();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(long agentId, String agentKey, long caseId, String caseRef,
                           long workItemId, String workItemRef) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailureConfiguration {

        @Bean
        @Primary
        FailsOnceContextSnapshotService failsOnceContextSnapshotService(
                JdbcClient jdbc, ObjectMapper objectMapper) {
            return new FailsOnceContextSnapshotService(jdbc, objectMapper);
        }
    }

    static class FailsOnceContextSnapshotService extends ContextSnapshotService {

        private final JdbcClient jdbc;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean observedTransaction = new AtomicBoolean();
        private final AtomicBoolean failFirstAttempt = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> blockedBuildStarted = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> blockedBuildRelease = new AtomicReference<>();

        FailsOnceContextSnapshotService(JdbcClient jdbc, ObjectMapper objectMapper) {
            super(jdbc, objectMapper);
            this.jdbc = jdbc;
        }

        @Override
        public Map<String, Object> build(String caseRef) {
            observedTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            attempts.incrementAndGet();
            CountDownLatch started = blockedBuildStarted.get();
            CountDownLatch release = blockedBuildRelease.get();
            if (started != null && release != null) {
                started.countDown();
                await(release);
            }
            if (failFirstAttempt.compareAndSet(true, false)) {
                jdbc.sql("SELECT missing_reconstruction_column FROM cases")
                        .query(String.class)
                        .list();
            }
            return super.build(caseRef);
        }

        void reset() {
            attempts.set(0);
            observedTransaction.set(false);
            failFirstAttempt.set(true);
            blockedBuildStarted.set(null);
            blockedBuildRelease.set(null);
        }

        void blockNextBuild() {
            attempts.set(0);
            observedTransaction.set(false);
            failFirstAttempt.set(false);
            blockedBuildStarted.set(new CountDownLatch(1));
            blockedBuildRelease.set(new CountDownLatch(1));
        }

        boolean awaitBlockedBuild() {
            CountDownLatch started = blockedBuildStarted.get();
            return started != null && await(started);
        }

        void releaseBlockedBuild() {
            CountDownLatch release = blockedBuildRelease.get();
            if (release != null) {
                release.countDown();
            }
        }

        int attempts() {
            return attempts.get();
        }

        boolean observedTransaction() {
            return observedTransaction.get();
        }

        private boolean await(CountDownLatch latch) {
            try {
                return latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while coordinating Run test", interrupted);
            }
        }
    }
}
