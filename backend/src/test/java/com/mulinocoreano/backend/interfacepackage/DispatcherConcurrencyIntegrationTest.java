package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DispatcherConcurrencyIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ContextSnapshotService contextSnapshotService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void concurrentAgentDeactivationWaitsForDispatchSchedulingToFinish() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Fixture fixture = transactions.execute(status -> createFixture());
        assertThat(fixture).isNotNull();

        CountDownLatch schedulingReached = new CountDownLatch(1);
        CountDownLatch continueScheduling = new CountDownLatch(1);
        CountDownLatch deactivationStarted = new CountDownLatch(1);
        AtomicLong deactivationBackendPid = new AtomicLong();
        RunService pausingRunService = new PausingRunService(
                jdbc, objectMapper, contextSnapshotService,
                schedulingReached, continueScheduling);
        DispatcherService dispatcher = new DispatcherService(
                jdbc, objectMapper, new WaitingConditionMatcher(), pausingRunService);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<EventDispatchResponse> dispatched = executor.submit(() -> transactions.execute(status -> {
                EventDispatchResponse response = dispatcher.ingest(new CreateEventRequest(
                        "SUPPLIER_EMAIL_RECEIVED", unique("MSG"), fixture.caseRef(), null,
                        Map.of("supplierId", 404)));
                assertThat(eventCount(response.eventId())).isEqualTo(1);
                assertThat(waitingStatus(fixture.waitingId())).isEqualTo("SATISFIED");
                status.setRollbackOnly();
                return response;
            }));

            assertThat(schedulingReached.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            Future<?> deactivated = executor.submit(() -> transactions.executeWithoutResult(status -> {
                deactivationBackendPid.set(jdbc.sql("SELECT pg_backend_pid()")
                        .query(Long.class)
                        .single());
                deactivationStarted.countDown();
                jdbc.sql("UPDATE agents SET is_active=FALSE WHERE agent_id=:agentId")
                        .param("agentId", fixture.agentId())
                        .update();
            }));

            assertThat(deactivationStarted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            awaitBlockedLock(deactivationBackendPid.get());
            assertThat(deactivated.isDone()).isFalse();

            continueScheduling.countDown();
            EventDispatchResponse response = dispatched.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            deactivated.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(response.scheduledRuns()).hasSize(1);
            assertThat(agentIsActive(fixture.agentId())).isFalse();
        } finally {
            continueScheduling.countDown();
            executor.shutdownNow();
            executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            transactions.executeWithoutResult(status -> deleteFixture(fixture));
        }
    }

    private void awaitBlockedLock(long backendPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean blocked = jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks
                        WHERE pid=:pid AND NOT granted
                    )
                    """)
                    .param("pid", backendPid)
                    .query(Boolean.class)
                    .single();
            if (blocked) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Concurrent agent deactivation never waited for the dispatch lock");
    }

    private Fixture createFixture() {
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:agentKey, 'Dispatcher concurrency test agent')
                RETURNING agent_id
                """)
                .param("agentKey", unique("AG"))
                .query(Long.class)
                .single();
        String caseRef = unique("CASE");
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:caseRef, 'Dispatcher concurrency test', 'Preserve scheduling order', 'ACT')
                RETURNING case_id
                """)
                .param("caseRef", caseRef)
                .query(Long.class)
                .single();
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id)
                VALUES (:workItemRef, :caseId, 'Wait for supplier', 'WAITING', :agentId)
                RETURNING work_item_id
                """)
                .param("workItemRef", unique("WI"))
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        long waitingId = jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref, work_item_id, condition_type, condition_payload, reason)
                VALUES (:waitingRef, :workItemId, 'SUPPLIER_REPLY',
                        '{"supplier_id":404}'::jsonb, 'Await supplier reply')
                RETURNING waiting_condition_id
                """)
                .param("waitingRef", unique("WAIT"))
                .param("workItemId", workItemId)
                .query(Long.class)
                .single();
        return new Fixture(agentId, caseId, caseRef, workItemId, waitingId);
    }

    private void deleteFixture(Fixture fixture) {
        jdbc.sql("DELETE FROM waiting_conditions WHERE waiting_condition_id=:waitingId")
                .param("waitingId", fixture.waitingId())
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

    private long eventCount(long eventId) {
        return jdbc.sql("SELECT count(*) FROM events WHERE event_id=:eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }

    private String waitingStatus(long waitingId) {
        return jdbc.sql("""
                SELECT status::text FROM waiting_conditions
                WHERE waiting_condition_id=:waitingId
                """)
                .param("waitingId", waitingId)
                .query(String.class)
                .single();
    }

    private boolean agentIsActive(long agentId) {
        return jdbc.sql("SELECT is_active FROM agents WHERE agent_id=:agentId")
                .param("agentId", agentId)
                .query(Boolean.class)
                .single();
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record Fixture(long agentId, long caseId, String caseRef,
                           long workItemId, long waitingId) { }

    private static final class PausingRunService extends RunService {
        private final CountDownLatch schedulingReached;
        private final CountDownLatch continueScheduling;

        private PausingRunService(
                JdbcClient jdbc, ObjectMapper objectMapper,
                ContextSnapshotService contextSnapshotService,
                CountDownLatch schedulingReached, CountDownLatch continueScheduling) {
            super(jdbc, objectMapper, contextSnapshotService);
            this.schedulingReached = schedulingReached;
            this.continueScheduling = continueScheduling;
        }

        @Override
        public Optional<RunDto> tryCreateRun(CreateRunRequest request, Long triggerEventId) {
            schedulingReached.countDown();
            try {
                if (!continueScheduling.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Timed out waiting to continue Run scheduling");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Run scheduling was interrupted", interrupted);
            }
            return super.tryCreateRun(request, triggerEventId);
        }
    }
}
