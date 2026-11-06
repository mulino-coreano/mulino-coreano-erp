package com.mulinocoreano.backend.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.mulinocoreano.backend.interfacepackage.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=run_claim_concurrency_it",
            "spring.flyway.clean-disabled=false",
            "spring.datasource.hikari.schema=run_claim_concurrency_it"
        })
class RunClaimConcurrencyIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired org.jooq.DSLContext dsl;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired PlatformTransactionManager manager;
    @Autowired Flyway flyway;

    @BeforeEach
    void clean() {
        assertThat(flyway.getConfiguration().getSchemas())
                .containsExactly("run_claim_concurrency_it");
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void concurrentWorkersClaimExactlyOneExecution() throws Exception {
        fixture();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures =
                    List.of(
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return execution.claim("worker-1");
                                    }),
                            pool.submit(
                                    () -> {
                                        start.await();
                                        return execution.claim("worker-2");
                                    }));
            start.countDown();
            int claimed = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS).isPresent()) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
        assertThat(
                        jdbc.sql("SELECT count(*) FROM runs WHERE status='RUNNING'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void sameWorkerCannotClaimTwoRunsEvenConcurrently() throws Exception {
        fixture();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s -> {
                            jdbc.sql(
                                            "INSERT INTO"
                                                + " work_items(work_item_ref,case_id,title,assigned_agent_id)"
                                                + " SELECT"
                                                + " 'WI-SECOND',case_id,'Second',assigned_agent_id"
                                                + " FROM work_items WHERE work_item_ref='WI-CLAIM'")
                                    .update();
                            runs.createRun(
                                    new CreateRunRequest(
                                            "SUPPLY_CHAIN", "CASE-CLAIM", "WI-SECOND", "CODEX"),
                                    null);
                        });
        var start = new CountDownLatch(1);
        java.util.concurrent.Callable<Integer> request =
                () -> {
                    start.await();
                    try {
                        return execution.claim("same-worker").isPresent() ? 200 : 204;
                    } catch (org.springframework.web.server.ResponseStatusException e) {
                        return e.getStatusCode().value();
                    }
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(request);
            var two = pool.submit(request);
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        jdbc.sql("SELECT count(*) FROM runs WHERE status='RUNNING'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void claimPollResumesADueScheduledWaitWithoutAnExtraDispatchCall() {
        fixture();
        var first = execution.claim("worker").orElseThrow();
        execution.finish(
                first.runRef(),
                "worker",
                first.leaseToken(),
                "WAITING",
                "Review later",
                List.of(
                        new RunExecutionService.Wait(
                                "SCHEDULED_TIME",
                                java.util.Map.of(
                                        "dueAt",
                                        java.time.Instant.now().plusSeconds(3600).toString()),
                                "Review later")));
        jdbc.sql("UPDATE waiting_conditions SET condition_payload=jsonb_build_object('dueAt',:due)")
                .param("due", java.time.Instant.now().minusSeconds(1).toString())
                .update();
        var resumed = execution.claim("worker");
        assertThat(resumed).isPresent();
        assertThat(resumed.orElseThrow().runRef()).isNotEqualTo(first.runRef());
    }

    @Test
    void assignmentChangeAfterQueuePreventsClaim() {
        fixture();
        jdbc.sql(
                        "UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE"
                            + " agent_key='QC')")
                .update();
        assertThat(execution.claim("worker")).isEmpty();
        assertThat(jdbc.sql("SELECT status::text FROM runs").query(String.class).single())
                .isEqualTo("ABORTED");
    }

    @Test
    void capabilityRequiresTransactionAndExpiresAfterCommittedFinish() {
        fixture();
        var claim = execution.claim("worker").orElseThrow();
        var leases = new RunLeaseRepository(dsl);
        var caps = new DatabaseRunCapabilityAccess(leases);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                caps.requireLocked(
                                        claim.capabilityToken(), "SUPPLY_CHAIN", claim.caseRef()))
                .isInstanceOf(IllegalStateException.class);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s ->
                                caps.requireLocked(
                                        claim.capabilityToken(), "SUPPLY_CHAIN", claim.caseRef()));
        execution.finish(
                claim.runRef(),
                "worker",
                claim.leaseToken(),
                "WAITING",
                "Review later",
                List.of(
                        new RunExecutionService.Wait(
                                "SCHEDULED_TIME",
                                java.util.Map.of(
                                        "dueAt",
                                        java.time.Instant.now().plusSeconds(100).toString()),
                                "Review later")));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                s ->
                                                        caps.requireLocked(
                                                                claim.capabilityToken(),
                                                                "SUPPLY_CHAIN",
                                                                claim.caseRef())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void heartbeatCapsLeaseAtTotalRuntimeDeadline() {
        fixture();
        var claim = execution.claim("worker").orElseThrow();
        var deadline =
                jdbc.sql(
                                "UPDATE runs SET claimed_at=clock_timestamp()-interval '570"
                                    + " seconds' WHERE run_ref=:ref RETURNING claimed_at+interval"
                                    + " '600 seconds'")
                        .param("ref", claim.runRef())
                        .query((rs, n) -> rs.getTimestamp(1).toInstant())
                        .single();
        var receipt = execution.heartbeat(claim.runRef(), "worker", claim.leaseToken());
        assertThat(receipt.leaseExpiresAt()).isEqualTo(deadline);
        assertThat(receipt.status()).isEqualTo("RUNNING");
        assertThat(
                        jdbc.sql("SELECT lease_expires_at FROM runs WHERE run_ref=:ref")
                                .param("ref", claim.runRef())
                                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                                .single())
                .isEqualTo(deadline);
    }

    @Test
    void totalRuntimeExpiryAbortsWithoutAutomaticRetry() {
        fixture();
        var claim = execution.claim("worker").orElseThrow();
        jdbc.sql(
                        "UPDATE runs SET claimed_at=clock_timestamp()-interval '601"
                            + " seconds',lease_expires_at=clock_timestamp()+interval '60 seconds'"
                            + " WHERE run_ref=:ref")
                .param("ref", claim.runRef())
                .update();
        assertThat(execution.claim("next-worker")).isEmpty();
        assertThat(
                        jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:ref")
                                .param("ref", claim.runRef())
                                .query(String.class)
                                .single())
                .isEqualTo("ABORTED");
        assertThat(jdbc.sql("SELECT count(*) FROM runs").query(Long.class).single()).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT status::text FROM work_items WHERE"
                                            + " work_item_ref='WI-CLAIM'")
                                .query(String.class)
                                .single())
                .isEqualTo("BLOCKED");
        assertThat(
                        jdbc.sql("SELECT question FROM attention_requests WHERE status='OPEN'")
                                .query(String.class)
                                .single())
                .isEqualTo("Execution exceeded 600 seconds");
    }

    private void fixture() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        s -> {
                            long cid =
                                    jdbc.sql(
                                                    "INSERT INTO"
                                                        + " cases(case_ref,title,objective,intent_type)"
                                                        + " VALUES('CASE-CLAIM','Claim"
                                                        + " once','Claim','ACT') RETURNING case_id")
                                            .query(Long.class)
                                            .single();
                            jdbc.sql(
                                            "INSERT INTO"
                                                + " work_items(work_item_ref,case_id,title,assigned_agent_id)"
                                                + " SELECT 'WI-CLAIM',:c,'Claim once',agent_id FROM"
                                                + " agents WHERE agent_key='SUPPLY_CHAIN'")
                                    .param("c", cid)
                                    .update();
                            runs.createRun(
                                    new CreateRunRequest(
                                            "SUPPLY_CHAIN", "CASE-CLAIM", "WI-CLAIM", "CODEX"),
                                    null);
                        });
    }
}
