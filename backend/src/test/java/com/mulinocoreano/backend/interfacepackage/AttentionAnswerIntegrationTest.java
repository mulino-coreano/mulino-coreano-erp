package com.mulinocoreano.backend.interfacepackage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mulinocoreano.backend.security.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=attention_answer_it",
            "spring.flyway.clean-disabled=false",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public",
            "spring.datasource.hikari.schema=attention_answer_it"
        })
@AutoConfigureMockMvc
class AttentionAnswerIntegrationTest {
    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;
    @Autowired AttentionAnswerService service;
    @Autowired DispatcherService dispatcher;
    @Autowired CaseOverviewService overviews;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean RunService runs;
    long user, caseId, work, attention;

    @BeforeEach
    void fixture() {
        flyway.clean();
        flyway.migrate();
        user =
                jdbc.sql(
                                "INSERT INTO users(name,email,role)"
                                        + " VALUES('Operator','answer@test.invalid','OPERATOR')"
                                        + " RETURNING user_id")
                        .query(Long.class)
                        .single();
        caseId =
                jdbc.sql(
                                "INSERT INTO cases(case_ref,title,objective,intent_type)"
                                    + " VALUES('CASE-ANSWER','context','context','ACT') RETURNING"
                                    + " case_id")
                        .query(Long.class)
                        .single();
        work =
                jdbc.sql(
                                "INSERT INTO"
                                    + " work_items(work_item_ref,case_id,title,status,assigned_agent_id)"
                                    + " SELECT 'WI-ANSWER',:case,'context','BLOCKED',agent_id FROM"
                                    + " agents WHERE agent_key='ORCHESTRATOR' RETURNING"
                                    + " work_item_id")
                        .param("case", caseId)
                        .query(Long.class)
                        .single();
        attention = newAttention();
    }

    long newAttention() {
        return jdbc.sql(
                        "INSERT INTO"
                            + " attention_requests(case_id,work_item_id,reason_type,title,question,suggested_scope)"
                            + " VALUES(:case,:work,'MISSING_HUMAN_CONTEXT','Context','Which"
                            + " date?','THIS_ACTION') RETURNING attention_request_id")
                .param("case", caseId)
                .param("work", work)
                .query(Long.class)
                .single();
    }

    HumanActor actor() {
        return new HumanActor(
                "https://test.invalid/",
                "operator",
                user,
                "Operator",
                "MANAGER",
                Set.of("work:write", "erp:read"));
    }

    AttentionAnswerRequest request() {
        return new AttentionAnswerRequest("Friday", 1, AttentionAnswerRequest.Scope.THIS_ACTION);
    }

    JsonNode answer(String key) {
        return service.answer(attention, request(), actor(), key);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    void conflict(Runnable a) {
        assertThatThrownBy(a::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void answerRecordsTargetHumanAndQueuesOnceIncludingReplay() {
        var first = answer("same");
        assertThat(first.path("version").asInt()).isEqualTo(2);
        assertThat(first.path("resolvedByUserId").asLong()).isEqualTo(user);
        assertThat(first.path("workItemRef").asText()).isEqualTo("WI-ANSWER");
        assertThat(first.path("resume").path("status").asText()).isEqualTo("QUEUED");
        JsonNode view = mapper.valueToTree(overviews.overview("CASE-ANSWER"));
        JsonNode decision = view.path("decisions").get(0);
        assertThat(decision.path("decisionId").asLong()).isEqualTo(first.path("decisionId").asLong());
        assertThat(decision.path("sourceAttentionId").asText()).isEqualTo(Long.toString(attention));
        assertThat(answer("same")).isEqualTo(first);
        assertThat(count("decisions")).isEqualTo(1);
        assertThat(count("runs")).isEqualTo(1);
        assertThat(count("case_participants")).isEqualTo(1);
        assertThat(count("events")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_type FROM events").query(String.class).single())
                .isEqualTo("ATTENTION_ANSWER_RECORDED");
        assertThat(jdbc.sql("SELECT context_snapshot::text FROM runs").query(String.class).single())
                .contains("sourceAttentionId");
        assertThat(
                        jdbc.sql("SELECT metadata->>'sourceAttentionId' FROM decisions")
                                .query(Long.class)
                                .single())
                .isEqualTo(attention);
        conflict(() -> answer("different"));
        conflict(
                () ->
                        service.answer(
                                attention,
                                new AttentionAnswerRequest(
                                        "different", 1, AttentionAnswerRequest.Scope.THIS_CASE),
                                actor(),
                                "same"));
    }

    @Test
    void invalidVersionUpdatesAreRejectedWithoutChangingStoredVersion() {
        for (String invalid : List.of("NULL", "0", "-1")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.sql(
                                                    "UPDATE attention_requests SET version="
                                                            + invalid
                                                            + " WHERE attention_request_id=:id")
                                            .param("id", attention)
                                            .update())
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(
                            jdbc.sql(
                                            "SELECT version FROM attention_requests WHERE"
                                                + " attention_request_id=:id")
                                    .param("id", attention)
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(1);
        }
    }

    @Test
    void everyUpdateInvalidatesViewedVersion() {
        jdbc.sql(
                        "UPDATE attention_requests SET question='Changed',version=999 WHERE"
                                + " attention_request_id=:id")
                .param("id", attention)
                .update();
        assertThat(jdbc.sql("SELECT version FROM attention_requests").query(Integer.class).single())
                .isEqualTo(2);
        conflict(() -> answer("stale"));
        assertThat(count("decisions")).isZero();
    }

    @Test
    void otherAttentionAndApprovalWaitRemainUnresolved() {
        long other = newAttention();
        assertThat(answer("open").path("resume").path("status").asText())
                .isEqualTo("BLOCKED_ATTENTION");
        jdbc.sql(
                        "INSERT INTO"
                            + " waiting_conditions(waiting_ref,work_item_id,condition_type,reason)"
                            + " VALUES('WAIT-ANSWER',:work,'APPROVAL','Manager approval required')")
                .param("work", work)
                .update();
        var result =
                service.answer(
                        other,
                        new AttentionAnswerRequest(
                                "Approved", 1, AttentionAnswerRequest.Scope.THIS_CASE),
                        actor(),
                        "wait");
        assertThat(result.path("resume").path("status").asText()).isEqualTo("BLOCKED_WAITING");
        assertThat(
                        jdbc.sql("SELECT status::text FROM waiting_conditions")
                                .query(String.class)
                                .single())
                .isEqualTo("ACTIVE");
        assertThat(count("runs")).isZero();
        assertThat(count("purchase_orders")).isZero();
        assertThat(count("governance_decisions")).isZero();
    }

    @Test
    void generalContextAnswerCannotBecomeAnApprovalThroughEventIngestion() {
        jdbc.sql("UPDATE work_items SET status='WAITING' WHERE work_item_id=:id")
                .param("id", work).update();
        jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref,work_item_id,condition_type,condition_payload,reason)
                VALUES('WAIT-CONTEXT',:work,'APPROVAL',
                       jsonb_build_object('attention_request_id',:attention),'Human approval required')
                """).param("work", work).param("attention", attention).update();
        var operator = new HumanActor("https://test.invalid/", "operator", user,
                "Operator", "OPERATOR", Set.of("work:write", "erp:read"));
        service.answer(attention,
                new AttentionAnswerRequest("Approved", 1, AttentionAnswerRequest.Scope.THIS_ACTION),
                operator, "ordinary-context-answer");
        long eventsBefore = count("events");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", "context-is-not-approval", null, null,
                Map.of("attentionRequestId", attention))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("human approval");

        assertThat(jdbc.sql("SELECT status::text FROM waiting_conditions")
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                .param("id", work).query(String.class).single()).isEqualTo("WAITING");
        assertThat(count("events")).isEqualTo(eventsBefore);
        assertThat(count("runs")).isZero();
        assertThat(count("purchase_orders")).isZero();
    }

    @Test
    void authorityAndClosedCaseReject() {
        jdbc.sql("UPDATE attention_requests SET reason_type='AUTHORITY_REQUIRED'").update();
        conflict(
                () ->
                        service.answer(
                                attention,
                                new AttentionAnswerRequest(
                                        "Approved", 2, AttentionAnswerRequest.Scope.THIS_ACTION),
                                actor(),
                                "authority"));
        jdbc.sql("UPDATE attention_requests SET reason_type='MISSING_HUMAN_CONTEXT'").update();
        jdbc.sql("UPDATE cases SET status='CLOSED',resolved_at=CURRENT_TIMESTAMP").update();
        conflict(
                () ->
                        service.answer(
                                attention,
                                new AttentionAnswerRequest(
                                        "Friday", 3, AttentionAnswerRequest.Scope.THIS_CASE),
                                actor(),
                                "closed"));
        assertThat(count("decisions")).isZero();
        assertThat(count("events")).isZero();
    }

    @Test
    void revalidatesDatabaseRoleAndActiveState() {
        jdbc.sql("UPDATE users SET role='VIEWER' WHERE user_id=:id").param("id", user).update();
        assertThatThrownBy(() -> answer("role"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.sql("UPDATE users SET role='OPERATOR',is_active=false WHERE user_id=:id")
                .param("id", user)
                .update();
        assertThatThrownBy(() -> answer("inactive"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                service.answer(
                                        attention,
                                        request(),
                                        new ServiceActor(
                                                "issuer",
                                                "subject",
                                                "client",
                                                Set.of("work:write")),
                                        "service"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(count("decisions")).isZero();
    }

    @Test
    void failedQueueRollsBackEverything() {
        doReturn(new RunDto(0, "RUN-FAILED", "ORCHESTRATOR", "FAILED", java.time.Instant.now()))
                .when(runs)
                .createRun(any(), any());
        conflict(() -> answer("rollback"));
        for (String table :
                List.of("decisions", "case_participants", "events", "request_idempotency"))
            assertThat(count(table)).isZero();
        assertThat(
                        jdbc.sql("SELECT status::text FROM attention_requests")
                                .query(String.class)
                                .single())
                .isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT status::text FROM work_items").query(String.class).single())
                .isEqualTo("BLOCKED");
    }

    @Test
    void concurrentReplayQueuesOnlyOnce() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return answer("race");
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return answer("race");
                            });
            gate.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(count("runs")).isEqualTo(1);
        assertThat(count("decisions")).isEqualTo(1);
        conflict(() -> answer("new"));
    }

    @Test
    void httpRejectsScopeWideningMissingVersionUnauthenticatedAndUnknown() throws Exception {
        var auth = new ActorAuthenticationToken(actor());
        mvc.perform(
                        post("/api/v1/attention/" + attention + "/answer")
                                .contentType("application/json")
                                .header("Idempotency-Key", "policy")
                                .content(
                                        "{\"answer\":\"Friday\",\"expectedVersion\":1,\"scope\":\"POLICY\"}")
                                .with(authentication(auth)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/attention/" + attention + "/answer")
                                .contentType("application/json")
                                .header("Idempotency-Key", "missing")
                                .content("{\"answer\":\"Friday\",\"scope\":\"THIS_CASE\"}")
                                .with(authentication(auth)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/attention/" + attention + "/answer"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/attention/999999/answer")
                                .contentType("application/json")
                                .header("Idempotency-Key", "unknown")
                                .content(mapper.writeValueAsString(request()))
                                .with(authentication(auth)))
                .andExpect(status().isNotFound());
        assertThat(count("decisions")).isZero();
    }

    @Test
    void purchaseLinkedContextCannotBypassApproval() {
        long warehouse =
                jdbc.sql(
                                "INSERT INTO warehouses(name,type)"
                                        + " VALUES('Plant','AMBIENT') RETURNING warehouse_id")
                        .query(Long.class)
                        .single();
        jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) VALUES(:case,:warehouse)")
                .param("case", caseId)
                .param("warehouse", warehouse)
                .update();
        long plan =
                jdbc.sql(
                                "INSERT INTO"
                                    + " replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash)"
                                    + " VALUES('PLAN-ANSWER',:case,:warehouse,1,CURRENT_TIMESTAMP,30,CURRENT_DATE,'{}','{}',repeat('a',64),repeat('b',64))"
                                    + " RETURNING replenishment_plan_id")
                        .param("case", caseId)
                        .param("warehouse", warehouse)
                        .query(Long.class)
                        .single();
        long action =
                jdbc.sql(
                                "INSERT INTO"
                                    + " governance_actions(requested_by,action_type,resource_type,resource_id,payload,required_role,case_id,work_item_id,replenishment_plan_id,proposed_by_agent_id,proposal_version,proposal_hash)"
                                    + " SELECT"
                                    + " :user,'PURCHASE_PROPOSAL','REPLENISHMENT_PLAN',:plan,'{}','MANAGER',:case,:work,:plan,agent_id,1,repeat('b',64)"
                                    + " FROM agents WHERE agent_key='PROCUREMENT' RETURNING"
                                    + " governance_action_id")
                        .param("user", user)
                        .param("plan", plan)
                        .param("case", caseId)
                        .param("work", work)
                        .query(Long.class)
                        .single();
        jdbc.sql("UPDATE attention_requests SET governance_action_id=:action")
                .param("action", action)
                .update();
        jdbc.sql(
                        "INSERT INTO"
                            + " waiting_conditions(waiting_ref,work_item_id,condition_type,reason)"
                            + " VALUES('WAIT-PURCHASE',:work,'APPROVAL','Manager approval')")
                .param("work", work)
                .update();
        conflict(
                () ->
                        service.answer(
                                attention,
                                new AttentionAnswerRequest(
                                        "Approved", 2, AttentionAnswerRequest.Scope.THIS_CASE),
                                actor(),
                                "purchase"));
        assertThat(
                        jdbc.sql("SELECT status::text FROM governance_actions")
                                .query(String.class)
                                .single())
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.sql("SELECT status::text FROM waiting_conditions")
                                .query(String.class)
                                .single())
                .isEqualTo("ACTIVE");
        for (String table :
                List.of(
                        "purchase_orders",
                        "purchase_applications",
                        "governance_decisions",
                        "decisions",
                        "events",
                        "runs")) assertThat(count(table)).isZero();
    }

    @Test
    void preservesUnassignedAndLiveRunWork() {
        jdbc.sql("UPDATE work_items SET assigned_agent_id=NULL").update();
        assertThat(answer("unassigned").path("resume").path("status").asText())
                .isEqualTo("BLOCKED");
        attention = newAttention();
        jdbc.sql(
                        "UPDATE work_items SET assigned_agent_id=(SELECT agent_id FROM agents WHERE"
                                + " agent_key='ORCHESTRATOR')")
                .update();
        jdbc.sql(
                        "INSERT INTO runs(run_ref,agent_id,case_id,work_item_id,runtime,status)"
                            + " SELECT 'RUN-EXISTING',agent_id,:case,:work,'CODEX','QUEUED' FROM"
                            + " agents WHERE agent_key='ORCHESTRATOR'")
                .param("case", caseId)
                .param("work", work)
                .update();
        assertThat(answer("live").path("resume").path("status").asText()).isEqualTo("LIVE_RUN");
        assertThat(count("runs")).isEqualTo(1);
    }

    @Test
    void concurrentDifferentKeysHaveOneWinner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raceAnswer("a");
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raceAnswer("b");
                            });
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("runs")).isEqualTo(1);
        assertThat(count("decisions")).isEqualTo(1);
    }

    int raceAnswer(String key) {
        try {
            answer(key);
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoSpyBean AttentionAnswerRepository answers;

    @Test
    void blockerCommittedWhileWaitingForWorkLockIsObserved() throws Exception {
        var inserted = new CountDownLatch(1);
        var releaseInsert = new CountDownLatch(1);
        var lockingWork = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            lockingWork.countDown();
                            return invocation.callRealMethod();
                        })
                .when(answers)
                .lockWork(work);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var insert =
                    pool.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    transactionManager)
                                            .execute(
                                                    status -> {
                                                        jdbc.sql(
                                                                        "INSERT INTO"
                                                                            + " waiting_conditions(waiting_ref,work_item_id,condition_type,reason)"
                                                                            + " VALUES('WAIT-CONCURRENT',:work,'APPROVAL','Manager')")
                                                                .param("work", work)
                                                                .update();
                                                        inserted.countDown();
                                                        try {
                                                            if (!releaseInsert.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "release timeout");
                                                        } catch (InterruptedException e) {
                                                            throw new IllegalStateException(e);
                                                        }
                                                        return true;
                                                    }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var answering = pool.submit(() -> answer("concurrent-wait"));
            try {
                assertThat(lockingWork.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> answering.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseInsert.countDown();
            }
            assertThat(insert.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(answering.get(5, TimeUnit.SECONDS).path("resume").path("status").asText())
                    .isEqualTo("BLOCKED_WAITING");
        } finally {
            releaseInsert.countDown();
        }
        assertThat(count("runs")).isZero();
    }
}
