package com.mulinocoreano.backend.execution;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mulinocoreano.backend.interfacepackage.*;
import com.mulinocoreano.backend.followup.*;
import com.mulinocoreano.backend.security.WithTestActor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(
        properties = {
            "spring.flyway.schemas=purchase_execution_it",
            "spring.flyway.clean-disabled=false",
            "spring.datasource.hikari.schema=purchase_execution_it",
            "spring.flyway.init-sqls=CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public"
        })
@AutoConfigureMockMvc
@WithTestActor(service = true, capabilities = "worker:dispatch")
class PurchaseExecutionIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired RunService runs;
    @Autowired RunExecutionService execution;
    @Autowired RunLeaseRepository leases;
    @Autowired RunExecutionRepository executionRepository;
    @Autowired ExecutionContextBuilder contexts;
    @Autowired DispatcherService dispatcher;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;
    @MockitoBean ProcurementCompletionVerifier verifier;
    // This suite isolates the role verifier contract; real follow-up creation is covered by
    // PurchaseWorkflowIntegrationTest with actual plans and purchase applications.
    @MockitoBean ReplenishmentFollowupService followups;
    @Autowired ReplenishmentFollowupRepository followupRepository;
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("purchase_execution_it");
        flyway.clean();
        flyway.migrate();
        tx = new TransactionTemplate(manager);
    }

    @Test
    void trustedApprovalWaitRequiresACallerTransaction() {
        var f = fixture("PROCUREMENT");
        assertThatThrownBy(
                        () ->
                                execution.awaitPurchaseApproval(
                                        f.runId(), f.actionId(), "Approve purchase"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitAndRunTerminationCommitAtomicallyAndCannotBeDuplicatedOrDowngraded() {
        var f = fixture("PROCUREMENT");
        var result = await(f);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.outcome()).isEqualTo("WAITING");
        assertThat(workStatus(f)).isEqualTo("WAITING");
        assertThat(
                        jdbc.sql("SELECT capability_token_hash IS NULL FROM runs WHERE run_id=:id")
                                .param("id", f.runId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        jdbc.sql(
                                        "SELECT condition_payload->>'approval_id' FROM"
                                            + " waiting_conditions WHERE work_item_id=:id")
                                .param("id", f.workId())
                                .query(String.class)
                                .single())
                .isEqualTo(Long.toString(f.actionId()));
        assertThat(
                        jdbc.sql(
                                        "SELECT jsonb_typeof(condition_payload->'approval_id') FROM"
                                            + " waiting_conditions WHERE work_item_id=:id")
                                .param("id", f.workId())
                                .query(String.class)
                                .single())
                .isEqualTo("string");
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                execution.awaitPurchaseApproval(
                                                        f.runId(), f.actionId(), "Duplicate")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(waitCount(f)).isEqualTo(1);
        var receipt =
                execution.finish(
                        f.claim().runRef(),
                        f.worker(),
                        f.claim().leaseToken(),
                        "FAILED",
                        "Model crashed after proposal",
                        null);
        assertThat(receipt.alreadyFinished()).isTrue();
        assertThat(receipt.outcome()).isEqualTo("WAITING");
        assertThat(workStatus(f)).isEqualTo("WAITING");
    }

    @Test
    void rollbackRestoresLiveRunAndLeavesNoWaitOrFinishEvent() {
        var f = fixture("PROCUREMENT");
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s -> {
                                            execution.awaitPurchaseApproval(
                                                    f.runId(), f.actionId(), "Approve purchase");
                                            assertThat(workStatus(f)).isEqualTo("WAITING");
                                            throw new IllegalStateException("rollback probe");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback probe");
        assertThat(workStatus(f)).isEqualTo("IN_PROGRESS");
        assertThat(waitCount(f)).isZero();
        assertThat(
                        jdbc.sql("SELECT status::text FROM runs WHERE run_id=:id")
                                .param("id", f.runId())
                                .query(String.class)
                                .single())
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM events WHERE external_ref=:ref")
                                .param("ref", "run-finish-" + f.claim().runRef())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void unrelatedActionAndWrongRoleCannotEnterApprovalWait() {
        var f = fixture("PROCUREMENT");
        var other = fixture("PROCUREMENT");
        var wrongRole = fixture("QC");
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                execution.awaitPurchaseApproval(
                                                        f.runId(),
                                                        other.actionId(),
                                                        "Foreign action")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                execution.awaitPurchaseApproval(
                                                        wrongRole.runId(),
                                                        wrongRole.actionId(),
                                                        "Wrong role")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(waitCount(f)).isZero();
        assertThat(waitCount(wrongRole)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "BLOCKED", "EXPIRED"})
    void terminalActionCannotBeRegisteredAsPendingApproval(String state) {
        var f = fixture("PROCUREMENT");
        jdbc.sql(
                        "UPDATE governance_actions SET status=CAST(:state AS"
                            + " governance_action_status) WHERE governance_action_id=:id")
                .param("state", state)
                .param("id", f.actionId())
                .update();
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                execution.awaitPurchaseApproval(
                                                        f.runId(), f.actionId(), "Not pending")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(waitCount(f)).isZero();
        assertThat(workStatus(f)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void generalWorkerFinishStillRejectsAnArbitraryApprovalWait() throws Exception {
        var f = fixture("PROCUREMENT");
        mvc.perform(
                        post("/api/v1/internal/runs/finish")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "runRef",
                                                        f.claim().runRef(),
                                                        "workerId",
                                                        f.worker(),
                                                        "leaseToken",
                                                        f.claim().leaseToken(),
                                                        "outcome",
                                                        "WAITING",
                                                        "summary",
                                                        "Manufactured wait",
                                                        "waitingConditions",
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "APPROVAL",
                                                                        "payload",
                                                                        Map.of(
                                                                                "approval_id",
                                                                                Long.toString(
                                                                                        f
                                                                                                .actionId())),
                                                                        "reason",
                                                                        "Manufactured"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RESULT"));
        assertThat(waitCount(f)).isZero();
    }

    @Test
    void procurementDoneRequiresItsVerifierAndQcRemainsDenied() {
        var f = fixture("PROCUREMENT");
        assertThatThrownBy(
                        () ->
                                execution.finish(
                                        f.claim().runRef(),
                                        f.worker(),
                                        f.claim().leaseToken(),
                                        "DONE",
                                        "Unverified",
                                        null))
                .isInstanceOf(ResponseStatusException.class);
        when(verifier.verified(f.caseId(), f.workId())).thenReturn(true);
        var result =
                execution.finish(
                        f.claim().runRef(),
                        f.worker(),
                        f.claim().leaseToken(),
                        "DONE",
                        "Verified purchase results",
                        null);
        assertThat(result.outcome()).isEqualTo("DONE");
        var qc = fixture("QC");
        when(verifier.verified(qc.caseId(), qc.workId())).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                execution.finish(
                                        qc.claim().runRef(),
                                        qc.worker(),
                                        qc.claim().leaseToken(),
                                        "DONE",
                                        "No QC verifier",
                                        null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void absentProcurementVerifierFailsClosed() {
        var f = fixture("PROCUREMENT");
        var noVerifier =
                new RunExecutionService(
                        executionRepository,
                        leases,
                        contexts,
                        runs,
                        dispatcher,
                        mapper,
                        manager,
                        new StaticListableBeanFactory()
                                .getBeanProvider(ProcurementCompletionVerifier.class), followups, followupRepository);
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                noVerifier.finish(
                                                        f.claim().runRef(),
                                                        f.worker(),
                                                        f.claim().leaseToken(),
                                                        "DONE",
                                                        "No verifier",
                                                        null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void finalManagerApprovalResumesOnlyTheActionWorkItem() {
        var f = fixture("PROCUREMENT");
        await(f);
        long unrelated =
                jdbc.sql(
                                "INSERT INTO"
                                    + " work_items(work_item_ref,case_id,title,status,assigned_agent_id)"
                                    + " SELECT :ref,case_id,'Different"
                                    + " responsibility','WAITING',agent_id FROM runs WHERE"
                                    + " run_id=:run RETURNING work_item_id")
                        .param("ref", "WI-" + shortId())
                        .param("run", f.runId())
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "INSERT INTO"
                            + " waiting_conditions(waiting_ref,work_item_id,condition_type,condition_payload,reason)"
                            + " VALUES(:ref,:wi,'APPROVAL',jsonb_build_object('approval_id',:approval),'Unrelated"
                            + " wait')")
                .param("ref", "WAIT-" + shortId())
                .param("wi", unrelated)
                .param("approval", Long.toString(f.actionId()))
                .update();
        decide(f, "APPROVE", f.managerId());
        var result = dispatcher.ingest(event(f, null, null));
        assertThat(result.satisfiedWaiting()).hasSize(1);
        assertThat(result.scheduledRuns()).hasSize(1);
        assertThat(workStatus(f)).isEqualTo("READY");
        assertThat(
                        jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                                .param("id", unrelated)
                                .query(String.class)
                                .single())
                .isEqualTo("WAITING");
        assertThat(
                        jdbc.sql("SELECT work_item_id FROM events WHERE event_id=:id")
                                .param("id", result.eventId())
                                .query(Long.class)
                                .single())
                .isEqualTo(f.workId());
        assertThat(
                        jdbc.sql("SELECT user_id FROM events WHERE event_id=:id")
                                .param("id", result.eventId())
                                .query(Long.class)
                                .single())
                .isEqualTo(f.managerId());
    }

    @Test
    void conflictingCallerScopeCannotRedirectTheApprovedAction() {
        var f = fixture("PROCUREMENT");
        await(f);
        var other = fixture("PROCUREMENT");
        decide(f, "APPROVE", f.managerId());
        assertThatThrownBy(
                        () ->
                                dispatcher.ingest(
                                        event(
                                                f,
                                                other.claim().caseRef(),
                                                other.claim().workItemRef())))
                .isInstanceOf(InvalidInterfaceRequestException.class);
        assertThatThrownBy(
                        () ->
                                dispatcher.ingest(
                                        new CreateEventRequest(
                                                "CHANGE_REQUEST_APPROVED",
                                                "wrong-payload-" + shortId(),
                                                null,
                                                null,
                                                Map.of(
                                                        "approvalId",
                                                        f.actionId(),
                                                        "caseRef",
                                                        other.claim().caseRef()))))
                .isInstanceOf(InvalidInterfaceRequestException.class);
        assertThat(workStatus(f)).isEqualTo("WAITING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "INACTIVE"})
    void approvedActionRequiresAnActiveManager(String state) {
        var f = fixture("PROCUREMENT");
        await(f);
        decide(f, "APPROVE", f.managerId());
        if ("INACTIVE".equals(state))
            jdbc.sql("UPDATE users SET is_active=false WHERE user_id=:id")
                    .param("id", f.managerId())
                    .update();
        else
            jdbc.sql("UPDATE users SET role='OPERATOR' WHERE user_id=:id")
                    .param("id", f.managerId())
                    .update();
        assertThatThrownBy(() -> dispatcher.ingest(event(f, null, null)))
                .isInstanceOf(InvalidInterfaceRequestException.class);
        assertThat(workStatus(f)).isEqualTo("WAITING");
    }

    @Test
    void blockDecisionNeverWakesAnApprovalWait() {
        var f = fixture("PROCUREMENT");
        await(f);
        decide(f, "BLOCK", f.managerId());
        assertThatThrownBy(() -> dispatcher.ingest(event(f, null, null)))
                .isInstanceOf(InvalidInterfaceRequestException.class);
        var result =
                dispatcher.ingest(
                        new CreateEventRequest(
                                "CHANGE_REQUEST_BLOCKED",
                                "blocked-" + shortId(),
                                f.claim().caseRef(),
                                f.claim().workItemRef(),
                                Map.of("approvalId", f.actionId())));
        assertThat(result.satisfiedWaiting()).isEmpty();
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(workStatus(f)).isEqualTo("WAITING");
    }

    private RunExecutionService.Receipt await(Fixture f) {
        var receipt = new AtomicReference<RunExecutionService.Receipt>();
        assertThatCode(
                        () ->
                                receipt.set(
                                        tx.execute(
                                                s ->
                                                        execution.awaitPurchaseApproval(
                                                                f.runId(),
                                                                f.actionId(),
                                                                "Manager purchase approval"
                                                                    + " required"))))
                .doesNotThrowAnyException();
        return receipt.get();
    }

    private long waitCount(Fixture f) {
        return jdbc.sql("SELECT count(*) FROM waiting_conditions WHERE work_item_id=:id")
                .param("id", f.workId())
                .query(Long.class)
                .single();
    }

    private String workStatus(Fixture f) {
        return jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                .param("id", f.workId())
                .query(String.class)
                .single();
    }

    private CreateEventRequest event(Fixture f, String caseRef, String workRef) {
        return new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED",
                "approved-" + shortId(),
                caseRef,
                workRef,
                Map.of("approvalId", f.actionId()));
    }

    private void decide(Fixture f, String decision, long userId) {
        tx.executeWithoutResult(
                s -> {
                    jdbc.sql(
                                    "UPDATE governance_actions SET status=CAST(:state AS"
                                        + " governance_action_status) WHERE"
                                        + " governance_action_id=:id")
                            .param("state", "APPROVE".equals(decision) ? "APPROVED" : "BLOCKED")
                            .param("id", f.actionId())
                            .update();
                    jdbc.sql(
                                    "INSERT INTO"
                                        + " governance_decisions(governance_action_id,decided_by,decision,reason,is_final)"
                                        + " VALUES(:ga,:user,CAST(:decision AS"
                                        + " governance_decision_type),'Fixture decision',true)")
                            .param("ga", f.actionId())
                            .param("user", userId)
                            .param("decision", decision)
                            .update();
                });
    }

    private Fixture fixture(String role) {
        String id = shortId(),
                caseRef = "CASE-" + id,
                workRef = "WI-" + id,
                worker = "worker-" + id;
        long user =
                jdbc.sql(
                                "INSERT INTO users(name,email,password,role) VALUES('Purchase"
                                    + " manager',:email,'test-only','MANAGER') RETURNING user_id")
                        .param("email", id + "@example.test")
                        .query(Long.class)
                        .single();
        long caseId =
                jdbc.sql(
                                "INSERT INTO"
                                    + " cases(case_ref,title,objective,intent_type,opened_by_user_id)"
                                    + " VALUES(:ref,'Purchase execution','Purchase"
                                    + " safely','ACT',:user) RETURNING case_id")
                        .param("ref", caseRef)
                        .param("user", user)
                        .query(Long.class)
                        .single();
        long workId =
                jdbc.sql(
                                "INSERT INTO"
                                    + " work_items(work_item_ref,case_id,title,assigned_agent_id)"
                                    + " SELECT :ref,:case,'Prepare purchase',agent_id FROM agents"
                                    + " WHERE agent_key=:role RETURNING work_item_id")
                        .param("ref", workRef)
                        .param("case", caseId)
                        .param("role", role)
                        .query(Long.class)
                        .single();
        var run = runs.createRun(new CreateRunRequest(role, caseRef, workRef, "CODEX"), null);
        var claim = execution.claim(worker).orElseThrow();
        long warehouse =
                jdbc.sql(
                                "INSERT INTO warehouses(name,type) VALUES('Purchase fixture"
                                    + " warehouse','AMBIENT') RETURNING warehouse_id")
                        .query(Long.class)
                        .single();
        jdbc.sql("INSERT INTO planning_cases(case_id,warehouse_id) VALUES(:case,:warehouse)")
                .param("case", caseId)
                .param("warehouse", warehouse)
                .update();
        long sourceWork =
                jdbc.sql(
                                "INSERT INTO"
                                    + " work_items(work_item_ref,case_id,title,assigned_agent_id)"
                                    + " SELECT :ref,:case,'Source planning',agent_id FROM agents"
                                    + " WHERE agent_key='SUPPLY_CHAIN' RETURNING work_item_id")
                        .param("ref", "WI-" + shortId())
                        .param("case", caseId)
                        .query(Long.class)
                        .single();
        long plan =
                jdbc.sql(
                                """
INSERT INTO replenishment_plans(plan_ref,case_id,warehouse_id,version,as_of,horizon_days,target_date,source_snapshot,result,source_hash,plan_hash,created_by_work_item_id)
VALUES(:ref,:case,:warehouse,1,clock_timestamp(),30,CURRENT_DATE+29,'{}'::jsonb,'{"status":"READY"}'::jsonb,:hash,:hash,:source)
RETURNING replenishment_plan_id
""")
                        .param("ref", "PLAN-" + UUID.randomUUID())
                        .param("case", caseId)
                        .param("warehouse", warehouse)
                        .param("hash", "0".repeat(64))
                        .param("source", sourceWork)
                        .query(Long.class)
                        .single();
        jdbc.sql(
                        "UPDATE work_items SET"
                            + " procurement_plan_id=:plan,procurement_outcome='PROPOSED' WHERE"
                            + " work_item_id=:wi")
                .param("plan", plan)
                .param("wi", workId)
                .update();
        long action =
                jdbc.sql(
                                """
INSERT INTO governance_actions(requested_by,action_type,resource_type,resource_id,payload,required_role,
    case_id,work_item_id,replenishment_plan_id,proposed_by_agent_id,proposal_version,proposal_hash)
SELECT :user,'PURCHASE_PROPOSAL','REPLENISHMENT_PLAN',:plan,'{}'::jsonb,'MANAGER',:case,:wi,:plan,agent_id,1,:hash
FROM agents WHERE agent_key='PROCUREMENT' RETURNING governance_action_id
""")
                        .param("user", user)
                        .param("plan", plan)
                        .param("case", caseId)
                        .param("wi", workId)
                        .param("hash", "0".repeat(64))
                        .query(Long.class)
                        .single();
        return new Fixture(caseId, workId, run.runId(), action, user, worker, claim);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(
            long caseId,
            long workId,
            long runId,
            long actionId,
            long managerId,
            String worker,
            RunExecutionService.Claim claim) {}
}
