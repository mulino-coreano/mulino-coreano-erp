package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class DispatcherIntegrationTest {

    @Autowired
    DispatcherService dispatcher;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void supplierReplyMakesEveryMatchingWaitingWorkItemReadyAndSchedulesAuditableRuns() {
        Fixture first = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":42}", "WAITING");
        Fixture second = waitingWorkItem(first.caseId(), first.caseRef(), first.agentId(),
                "SUPPLIER_REPLY", "{\"po_ref\":\"PO-104\"}", "WAITING");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), first.caseRef(), null,
                Map.of("supplierId", 42, "poRef", "PO-104")));

        assertThat(result.satisfiedWaiting()).containsExactlyInAnyOrder(first.waitingRef(), second.waitingRef());
        assertThat(result.readyWorkItems()).containsExactlyInAnyOrder(first.workItemRef(), second.workItemRef());
        assertThat(result.scheduledRuns()).hasSize(2);
        assertThat(workItemStatus(first.workItemId())).isEqualTo("READY");
        assertThat(waitingStatus(first.waitingId())).isEqualTo("SATISFIED");
        assertThat(waitingResolvedBy(first.waitingId())).isEqualTo(result.eventId());
        assertThat(runCountForEvent(result.eventId())).isEqualTo(2);
        assertThat(runCountForEventAndWorkItem(result.eventId(), first.workItemId())).isEqualTo(1);
    }

    @Test
    void duplicateExternalEventReturnsSameEventWithoutAnotherTransitionOrRun() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":73}", "WAITING");
        String externalRef = unique("msg");
        CreateEventRequest request = new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", externalRef, fixture.caseRef(), null,
                Map.of("supplierId", 73));

        EventDispatchResponse first = dispatcher.ingest(request);
        EventDispatchResponse duplicate = dispatcher.ingest(request);

        assertThat(duplicate.eventId()).isEqualTo(first.eventId());
        assertThat(duplicate.satisfiedWaiting()).isEmpty();
        assertThat(duplicate.readyWorkItems()).isEmpty();
        assertThat(duplicate.scheduledRuns()).isEmpty();
        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED", externalRef)).isEqualTo(1);
        assertThat(runCountForEvent(first.eventId())).isEqualTo(1);
    }

    @Test
    void eventTypeIsCanonicalizedBeforePersistenceAndMatching() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":72}", "WAITING");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "  SUPPLIER_EMAIL_RECEIVED  ", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 72)));

        assertThat(eventType(result.eventId())).isEqualTo("SUPPLIER_EMAIL_RECEIVED");
        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
    }

    @Test
    void activeRunPreventsAnotherScheduledRunWithoutHidingReadyTransition() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":74}", "WAITING");
        insertActiveRun(fixture);

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 74)));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("READY");
        assertThat(runCountForWorkItem(fixture.workItemId())).isEqualTo(1);
        assertThat(runCountForEvent(result.eventId())).isZero();
    }

    @Test
    void failedContextReconstructionIsReportedAndRaisesOperatorAttention() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":75}", "WAITING");
        ContextSnapshotService failingContext = new ContextSnapshotService(jdbc, objectMapper) {
            @Override
            public Map<String, Object> build(String caseRef) {
                throw new IllegalStateException("context source unavailable");
            }
        };
        RunService failingRunService = new RunService(jdbc, objectMapper, failingContext);
        DispatcherService failingDispatcher = new DispatcherService(
                jdbc, objectMapper, new WaitingConditionMatcher(), failingRunService);

        EventDispatchResponse result = failingDispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 75)));

        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(result.failedRuns()).hasSize(1);
        assertThat(runStatus(result.failedRuns().getFirst())).isEqualTo("FAILED");
        assertThat(openAttentionCount(fixture.workItemId(), "MATERIAL_EXCEPTION")).isEqualTo(1);
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("READY");
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadIsRejected() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":75}", "WAITING");
        String externalRef = unique("msg");
        dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", externalRef, fixture.caseRef(), null,
                Map.of("supplierId", 75)));

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", externalRef, fixture.caseRef(), null,
                Map.of("supplierId", 76))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("different event content");
    }

    @Test
    void publicEventIngestionRequiresNonBlankIdempotencyKey() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":77}", "WAITING");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", "  ", fixture.caseRef(), null,
                Map.of("supplierId", 77))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalRef");
    }

    @Test
    void eventPayloadIdentityCannotContradictResolvedScope() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":78}", "WAITING");
        String externalRef = unique("msg");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", externalRef, fixture.caseRef(), fixture.workItemRef(),
                Map.of("case_id", fixture.caseId() + 1))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("payload case_id does not match resolved caseId");

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED", externalRef)).isZero();
    }

    @Test
    void equivalentNumericIdentityAliasesAreOverwrittenWithCanonicalScope() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":79}", "WAITING");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("case_id", new BigDecimal(fixture.caseId() + ".0"));
        payload.put("caseRef", fixture.caseRef());
        payload.put("workItemId", new BigDecimal(fixture.workItemId() + ".00"));
        payload.put("work_item_ref", fixture.workItemRef());

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), fixture.workItemRef(),
                payload));

        CanonicalScope stored = canonicalScope(result.eventId());
        assertThat(stored).isEqualTo(new CanonicalScope(
                Long.toString(fixture.caseId()), Long.toString(fixture.caseId()),
                fixture.caseRef(), fixture.caseRef(),
                Long.toString(fixture.workItemId()), Long.toString(fixture.workItemId()),
                fixture.workItemRef(), fixture.workItemRef()));
    }

    @Test
    void mismatchedSupplierReplyLeavesWaitingStateUntouched() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":81}", "WAITING");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 82)));

        assertThat(result.satisfiedWaiting()).isEmpty();
        assertThat(result.readyWorkItems()).isEmpty();
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("WAITING");
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
        assertThat(runCountForEvent(result.eventId())).isZero();
    }

    @Test
    void nonObjectWaitingPayloadFailsClosedWithoutAbortingOtherCandidates() {
        Fixture malformed = waitingFixture("SUPPLIER_REPLY", "[]", "WAITING");
        Fixture valid = waitingWorkItem(
                malformed.caseId(), malformed.caseRef(), malformed.agentId(),
                "SUPPLIER_REPLY", "{\"supplier_id\":82}", "WAITING");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), malformed.caseRef(), null,
                Map.of("supplierId", 82)));

        assertThat(result.satisfiedWaiting()).containsExactly(valid.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(valid.workItemRef());
        assertThat(waitingStatus(malformed.waitingId())).isEqualTo("ACTIVE");
        assertThat(workItemStatus(malformed.workItemId())).isEqualTo("WAITING");
        assertThat(waitingStatus(valid.waitingId())).isEqualTo("SATISFIED");
        assertThat(workItemStatus(valid.workItemId())).isEqualTo("READY");
    }

    @Test
    void emailSentMatchingCaseWakesAndSchedulesAssignedAgent() {
        Fixture fixture = waitingFixture("EMAIL_SENT", "{}", "WAITING");
        jdbc.sql("UPDATE waiting_conditions SET condition_payload=CAST(:payload AS jsonb) WHERE waiting_condition_id=:id")
                .param("payload", "{\"case_id\":" + fixture.caseId() + "}")
                .param("id", fixture.waitingId())
                .update();

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "EMAIL_SENT", unique("email"), fixture.caseRef(), fixture.workItemRef(),
                Map.of("caseId", fixture.caseId())));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).hasSize(1);
        assertThat(runCountForEventAndWorkItem(result.eventId(), fixture.workItemId())).isEqualTo(1);
    }

    @Test
    void approvalEventWakesMatchingWaitingWorkItemAndSchedulesAuditableRun() {
        Fixture fixture = waitingFixture("APPROVAL", "{}", "WAITING");
        long approverId = user("approval-attention");
        long attentionId = answeredAttention(fixture, approverId, "APPROVED");
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object(
                    'attention_request_id', :attentionId,
                    'expected_decision', 'APPROVED')
                WHERE waiting_condition_id=:waitingId
                """)
                .param("attentionId", attentionId)
                .param("waitingId", fixture.waitingId())
                .update();

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", unique("approval"), null, null,
                Map.of("attentionRequestId", attentionId, "decision", "APPROVED")));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).hasSize(1);
        assertThat(waitingResolvedBy(fixture.waitingId())).isEqualTo(result.eventId());
        assertThat(runCountForEventAndWorkItem(result.eventId(), fixture.workItemId())).isEqualTo(1);
        assertThat(eventAttribution(result.eventId())).isEqualTo(
                new EventAttribution("USER", approverId, fixture.caseId(), fixture.workItemId()));
    }

    @Test
    void approvalEventRejectsDecisionTextWithoutAnAuthoritativeApprovalIdentity() {
        Fixture fixture = waitingFixture(
                "APPROVAL", "{\"expected_decision\":\"APPROVED\"}", "WAITING");
        String externalRef = unique("approval");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", externalRef, null, null,
                Map.of("decision", "APPROVED"))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("approval identity");

        assertThat(eventCount("CHANGE_REQUEST_APPROVED", externalRef)).isZero();
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void governanceApprovalEventUsesThePersistedDecisionAndApprover() {
        Fixture fixture = waitingFixture("APPROVAL", "{}", "WAITING");
        long approverId = user("approval-governance");
        long approvalId = approvedGovernanceAction(approverId);
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object(
                    'approval_id', :approvalId,
                    'expected_decision', 'APPROVED')
                WHERE waiting_condition_id=:waitingId
                """)
                .param("approvalId", approvalId)
                .param("waitingId", fixture.waitingId())
                .update();

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", unique("approval"), null, null,
                Map.of("approvalId", approvalId)));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(eventAttribution(result.eventId())).isEqualTo(
                new EventAttribution("USER", approverId, null, null));
    }

    @Test
    void governanceApprovalRejectsAMissingDeclaredAuthoritativeResource() {
        long approverId = user("missing-governance-resource");
        long approvalId = approvedGovernanceAction(
                approverId, "WORK_ITEM", Integer.MAX_VALUE);
        String externalRef = unique("approval");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", externalRef, null, null,
                Map.of("approvalId", approvalId))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("authoritative resource does not exist");

        assertThat(eventCount("CHANGE_REQUEST_APPROVED", externalRef)).isZero();
    }

    @Test
    void governanceApprovalRejectsCallerScopeForAnUnmappedBusinessResource() {
        Fixture intended = waitingFixture("APPROVAL", "{}", "WAITING");
        Fixture unrelated = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":996}", "WAITING");
        long approverId = user("misrouted-governance-approval");
        long approvalId = approvedGovernanceAction(approverId);
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object('approval_id', :approvalId)
                WHERE waiting_condition_id=:waitingId
                """)
                .param("approvalId", approvalId)
                .param("waitingId", intended.waitingId())
                .update();
        String externalRef = unique("approval");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", externalRef, unrelated.caseRef(), null,
                Map.of("approvalId", approvalId))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("scope");

        assertThat(eventCount("CHANGE_REQUEST_APPROVED", externalRef)).isZero();
        assertThat(waitingStatus(intended.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void globallyScopedGovernanceApprovalRejectsClaimEvidenceCaseNarrowing() {
        Fixture fixture = waitingFixture("APPROVAL", "{}", "WAITING");
        long approverId = user("approval-claim-scope");
        long approvalId = approvedGovernanceAction(approverId);
        long claimId = claim(fixture.caseId());
        String evidenceRef = evidence(fixture.caseId());
        String externalRef = unique("approval");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "CHANGE_REQUEST_APPROVED", externalRef, null, null,
                Map.of("approvalId", approvalId,
                        "claimId", claimId,
                        "evidenceRef", evidenceRef))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("Claim/Evidence");

        assertThat(eventCount("CHANGE_REQUEST_APPROVED", externalRef)).isZero();
        assertThat(claimEvidenceCount(claimId, evidenceRef, "SUPPORTS")).isZero();
    }

    @Test
    void externalDataEventWakesMatchingWaitingWorkItemAndSchedulesAuditableRun() {
        Fixture fixture = waitingFixture("EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}", "WAITING");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "THIRD_PARTY_STOCK_REPORT_RECEIVED", unique("stock-report"), fixture.caseRef(), null,
                Map.of("source", "3PL")));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).hasSize(1);
        assertThat(waitingResolvedBy(fixture.waitingId())).isEqualTo(result.eventId());
        assertThat(runCountForEventAndWorkItem(result.eventId(), fixture.workItemId())).isEqualTo(1);
    }

    @Test
    void eventLinksValidatedEvidenceToAClaimInTheSameCase() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":999}", "READY");
        long claimId = claim(fixture.caseId());
        String evidenceRef = evidence(fixture.caseId());

        dispatcher.ingest(new CreateEventRequest(
                "INVENTORY_CHANGED", unique("inventory"), fixture.caseRef(), null,
                Map.of("claimId", claimId, "evidenceRef", evidenceRef,
                        "relation", "SUPPORTS")));

        assertThat(claimEvidenceCount(claimId, evidenceRef, "SUPPORTS")).isEqualTo(1);
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("READY");
    }

    @Test
    void eventRejectsClaimAndEvidenceFromDifferentCasesBeforeInsertion() {
        Fixture claimCase = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":998}", "READY");
        Fixture evidenceCase = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":997}", "READY");
        long claimId = claim(claimCase.caseId());
        String evidenceRef = evidence(evidenceCase.caseId());
        String externalRef = unique("inventory");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "INVENTORY_CHANGED", externalRef, claimCase.caseRef(), null,
                Map.of("claimId", claimId, "evidenceRef", evidenceRef))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("same Case");

        assertThat(eventCount("INVENTORY_CHANGED", externalRef)).isZero();
        assertThat(claimEvidenceCount(claimId, evidenceRef, "SUPPORTS")).isZero();
    }

    @Test
    void scopedDependencyStatusEventWakesDependentWorkItemAndSchedulesAuditableRun() {
        Fixture fixture = waitingFixture("DEPENDENCY_DONE", "{}", "WAITING");
        String dependencyRef = terminalDependency(fixture, "DONE");
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object('dependent_wi_ref', :dependencyRef)
                WHERE waiting_condition_id=:waitingId
                """)
                .param("dependencyRef", dependencyRef)
                .param("waitingId", fixture.waitingId())
                .update();

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "WORK_ITEM_STATUS_CHANGED", unique("status-change"), fixture.caseRef(), dependencyRef,
                Map.of("workItemRef", dependencyRef, "status", "DONE")));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).hasSize(1);
        assertThat(waitingResolvedBy(fixture.waitingId())).isEqualTo(result.eventId());
        assertThat(runCountForEventAndWorkItem(result.eventId(), fixture.workItemId())).isEqualTo(1);
    }

    @Test
    void dependencyStatusEventRejectsAClaimAboutANonexistentWorkItem() {
        String dependencyRef = unique("WI");
        Fixture fixture = waitingFixture("DEPENDENCY_DONE",
                "{\"dependent_wi_ref\":\"" + dependencyRef + "\"}", "WAITING");
        String externalRef = unique("status-change");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "WORK_ITEM_STATUS_CHANGED", externalRef, null, null,
                Map.of("workItemRef", dependencyRef, "status", "DONE"))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("workItemRef");

        assertThat(eventCount("WORK_ITEM_STATUS_CHANGED", externalRef)).isZero();
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void dependencyStatusEventRejectsContradictorySourceAliasesBeforeInsertion() {
        Fixture fixture = waitingFixture("DEPENDENCY_DONE", "{}", "WAITING");
        String dependencyRef = terminalDependency(fixture, "DONE");
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object('dependent_wi_ref', :dependencyRef)
                WHERE waiting_condition_id=:waitingId
                """)
                .param("dependencyRef", dependencyRef)
                .param("waitingId", fixture.waitingId())
                .update();
        String externalRef = unique("status-change");

        assertThatThrownBy(() -> dispatcher.ingest(new CreateEventRequest(
                "WORK_ITEM_STATUS_CHANGED", externalRef, fixture.caseRef(), dependencyRef,
                Map.of("workItemRef", dependencyRef,
                        "dependentWiRef", unique("WI"),
                        "status", "DONE"))))
                .isInstanceOf(InvalidInterfaceRequestException.class)
                .hasMessageContaining("Conflicting");

        assertThat(eventCount("WORK_ITEM_STATUS_CHANGED", externalRef)).isZero();
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DONE", "CANCELLED"})
    void matchingEventDoesNotChangeTerminalWorkItem(String terminalStatus) {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":91}", terminalStatus);

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 91)));

        assertThat(result.satisfiedWaiting()).isEmpty();
        assertThat(result.readyWorkItems()).isEmpty();
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo(terminalStatus);
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void workItemWithMultipleActiveConditionsWaitsUntilAllAreSatisfied() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":121}", "WAITING");
        String emailWaitingRef = unique("WAIT");
        jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref, work_item_id, condition_type, condition_payload, reason)
                VALUES
                    (:ref, :workItemId, 'EMAIL_SENT',
                     jsonb_build_object('case_id', :caseId), 'Second required condition')
                """)
                .param("ref", emailWaitingRef)
                .param("workItemId", fixture.workItemId())
                .param("caseId", fixture.caseId())
                .update();

        EventDispatchResponse supplier = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 121)));

        assertThat(supplier.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(supplier.readyWorkItems()).isEmpty();
        assertThat(supplier.scheduledRuns()).isEmpty();
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("WAITING");

        EventDispatchResponse email = dispatcher.ingest(new CreateEventRequest(
                "EMAIL_SENT", unique("email"), fixture.caseRef(), fixture.workItemRef(),
                Map.of("caseId", fixture.caseId())));

        assertThat(email.satisfiedWaiting()).containsExactly(emailWaitingRef);
        assertThat(email.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(email.scheduledRuns()).hasSize(1);
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("READY");
    }

    @Test
    void unassignedWorkItemRaisesAttentionWhenWaitResolvesWithoutRunnableAgent() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":131}", "WAITING");
        jdbc.sql("UPDATE work_items SET assigned_agent_id=NULL WHERE work_item_id=:workItemId")
                .param("workItemId", fixture.workItemId())
                .update();

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", unique("msg"), fixture.caseRef(), null,
                Map.of("supplierId", 131)));

        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM attention_requests
                WHERE work_item_id=:workItemId
                  AND status='OPEN'
                  AND reason_type='MISSING_HUMAN_CONTEXT'
                """)
                .param("workItemId", fixture.workItemId())
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void inactiveAssignedAgentDoesNotRollBackTheEventOrResolvedWait() {
        Fixture fixture = waitingFixture("SUPPLIER_REPLY", "{\"supplier_id\":132}", "WAITING");
        jdbc.sql("UPDATE agents SET is_active=FALSE WHERE agent_id=:agentId")
                .param("agentId", fixture.agentId())
                .update();
        String externalRef = unique("msg");

        EventDispatchResponse result = dispatcher.ingest(new CreateEventRequest(
                "SUPPLIER_EMAIL_RECEIVED", externalRef, fixture.caseRef(), null,
                Map.of("supplierId", 132)));

        assertThat(result.satisfiedWaiting()).containsExactly(fixture.waitingRef());
        assertThat(result.readyWorkItems()).containsExactly(fixture.workItemRef());
        assertThat(result.scheduledRuns()).isEmpty();
        assertThat(result.failedRuns()).isEmpty();
        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED", externalRef)).isEqualTo(1);
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("SATISFIED");
        assertThat(workItemStatus(fixture.workItemId())).isEqualTo("READY");
        assertThat(openAttentionCount(
                fixture.workItemId(), "MISSING_HUMAN_CONTEXT")).isEqualTo(1);
    }

    @Test
    void scheduledDispatchSatisfiesOnlyDueConditionsThroughSyntheticEvent() {
        Fixture due = waitingFixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}", "WAITING");
        Fixture future = waitingFixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}", "WAITING");

        EventDispatchResponse result = dispatcher.dispatchScheduled();

        assertThat(result.satisfiedWaiting()).contains(due.waitingRef()).doesNotContain(future.waitingRef());
        assertThat(result.readyWorkItems()).contains(due.workItemRef()).doesNotContain(future.workItemRef());
        assertThat(eventType(result.eventId())).isEqualTo("DISPATCH_REQUESTED");
        assertThat(eventPayloadValue(result.eventId(), "source")).isEqualTo("MANUAL");
        assertThat(waitingResolvedBy(due.waitingId())).isEqualTo(result.eventId());
        assertThat(waitingStatus(future.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void manualDispatchRecordsATruthfulRequestWhenNothingIsDue() {
        Fixture future = waitingFixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}", "WAITING");

        EventDispatchResponse result = dispatcher.dispatchScheduled();

        assertThat(eventType(result.eventId())).isEqualTo("DISPATCH_REQUESTED");
        assertThat(eventPayloadValue(result.eventId(), "source")).isEqualTo("MANUAL");
        assertThat(result.satisfiedWaiting()).isEmpty();
        assertThat(waitingStatus(future.waitingId())).isEqualTo("ACTIVE");
    }

    @Test
    void caseFilteredHistoryNeverRewritesAGlobalEventsDirectScope() {
        Fixture first = waitingFixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}", "WAITING");
        Fixture second = waitingFixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(1, ChronoUnit.HOURS) + "\"}", "WAITING");
        EventDispatchResponse dispatched = dispatcher.dispatchScheduled();

        EventDto throughFirst = eventFromHistory(first.caseRef(), dispatched.eventId());
        EventDto throughSecond = eventFromHistory(second.caseRef(), dispatched.eventId());
        EventDto unfiltered = eventFromHistory(null, dispatched.eventId());

        assertThat(throughFirst.caseRef()).isNull();
        assertThat(throughSecond.caseRef()).isNull();
        assertThat(unfiltered.caseRef()).isNull();
    }

    @Test
    void scheduledDispatchReevaluatesDependencyAgainstCurrentWorkItemState() {
        Fixture waiting = waitingFixture("DEPENDENCY_DONE", "{}", "WAITING");
        String dependencyRef = unique("WI");
        jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, resolved_at)
                VALUES
                    (:ref, :caseId, 'Completed dependency', 'DONE', :agentId, CURRENT_TIMESTAMP)
                """)
                .param("ref", dependencyRef)
                .param("caseId", waiting.caseId())
                .param("agentId", waiting.agentId())
                .update();
        jdbc.sql("""
                UPDATE waiting_conditions
                SET condition_payload=jsonb_build_object('dependent_wi_ref', :dependencyRef)
                WHERE waiting_condition_id=:waitingId
                """)
                .param("dependencyRef", dependencyRef)
                .param("waitingId", waiting.waitingId())
                .update();

        EventDispatchResponse result = dispatcher.dispatchScheduled();

        assertThat(result.satisfiedWaiting()).contains(waiting.waitingRef());
        assertThat(result.readyWorkItems()).contains(waiting.workItemRef());
        assertThat(waitingResolvedBy(waiting.waitingId())).isEqualTo(result.eventId());
        DependencyObservation observation = dependencyObservation(result.eventId(), dependencyRef);
        assertThat(observation.workItemRef()).isEqualTo(dependencyRef);
        assertThat(observation.status()).isEqualTo("DONE");
        assertThat(observation.observedAt()).isNotNull();
        assertThat(observation.propertyCount()).isEqualTo(3);
    }

    private Fixture waitingFixture(String conditionType, String conditionPayload, String workItemStatus) {
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:key, 'Dispatcher Test Agent')
                RETURNING agent_id
                """)
                .param("key", unique("AGENT"))
                .query(Long.class)
                .single();
        String caseRef = unique("CASE");
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:ref, 'Dispatcher test case', 'Wake only matching work', 'ACT')
                RETURNING case_id
                """)
                .param("ref", caseRef)
                .query(Long.class)
                .single();
        return waitingWorkItem(caseId, caseRef, agentId, conditionType, conditionPayload, workItemStatus);
    }

    private Fixture waitingWorkItem(long caseId, String caseRef, long agentId,
                                    String conditionType, String conditionPayload, String workItemStatus) {
        String workItemRef = unique("WI");
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, resolved_at,
                     metadata)
                VALUES
                    (:ref, :caseId, 'Dispatcher test work', :status::work_item_status, :agentId,
                     CASE WHEN :status IN ('DONE', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE NULL END,
                     '{"businessRef":{"type":"purchase_order","ref":"PO-104"}}'::jsonb)
                RETURNING work_item_id
                """)
                .param("ref", workItemRef)
                .param("caseId", caseId)
                .param("status", workItemStatus)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        String waitingRef = unique("WAIT");
        long waitingId = jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref, work_item_id, condition_type, condition_payload, reason)
                VALUES (:ref, :workItemId, :type::waiting_condition_type,
                        CAST(:payload AS jsonb), 'Dispatcher integration test')
                RETURNING waiting_condition_id
                """)
                .param("ref", waitingRef)
                .param("workItemId", workItemId)
                .param("type", conditionType)
                .param("payload", conditionPayload)
                .query(Long.class)
                .single();
        return new Fixture(caseId, caseRef, workItemId, workItemRef, waitingId, waitingRef, agentId);
    }

    private String workItemStatus(long workItemId) {
        return jdbc.sql("SELECT status::text FROM work_items WHERE work_item_id=:id")
                .param("id", workItemId).query(String.class).single();
    }

    private String waitingStatus(long waitingId) {
        return jdbc.sql("SELECT status::text FROM waiting_conditions WHERE waiting_condition_id=:id")
                .param("id", waitingId).query(String.class).single();
    }

    private long waitingResolvedBy(long waitingId) {
        return jdbc.sql("SELECT resolved_by_event_id FROM waiting_conditions WHERE waiting_condition_id=:id")
                .param("id", waitingId).query(Long.class).single();
    }

    private long runCountForEvent(long eventId) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE trigger_event_id=:eventId")
                .param("eventId", eventId).query(Long.class).single();
    }

    private long runCountForEventAndWorkItem(long eventId, long workItemId) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE trigger_event_id=:eventId AND work_item_id=:workItemId")
                .param("eventId", eventId).param("workItemId", workItemId)
                .query(Long.class).single();
    }

    private long runCountForWorkItem(long workItemId) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE work_item_id=:workItemId")
                .param("workItemId", workItemId)
                .query(Long.class)
                .single();
    }

    private String runStatus(String runRef) {
        return jdbc.sql("SELECT status::text FROM runs WHERE run_ref=:runRef")
                .param("runRef", runRef)
                .query(String.class)
                .single();
    }

    private long openAttentionCount(long workItemId, String reasonType) {
        return jdbc.sql("""
                SELECT count(*)
                FROM attention_requests
                WHERE work_item_id=:workItemId
                  AND status='OPEN'
                  AND reason_type=:reasonType::attention_reason_type
                """)
                .param("workItemId", workItemId)
                .param("reasonType", reasonType)
                .query(Long.class)
                .single();
    }

    private void insertActiveRun(Fixture fixture) {
        jdbc.sql("""
                INSERT INTO runs
                    (run_ref, agent_id, case_id, work_item_id, runtime, status)
                VALUES
                    (:runRef, :agentId, :caseId, :workItemId, 'CODEX', 'RUNNING')
                """)
                .param("runRef", unique("RUN"))
                .param("agentId", fixture.agentId())
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .update();
    }

    private String terminalDependency(Fixture fixture, String status) {
        String dependencyRef = unique("WI");
        jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id, resolved_at)
                VALUES
                    (:ref, :caseId, 'Terminal dependency', :status::work_item_status,
                     :agentId, CURRENT_TIMESTAMP)
                """)
                .param("ref", dependencyRef)
                .param("caseId", fixture.caseId())
                .param("status", status)
                .param("agentId", fixture.agentId())
                .update();
        return dependencyRef;
    }

    private long user(String suffix) {
        return jdbc.sql("""
                INSERT INTO users (name, email, password, role)
                VALUES ('Dispatcher approver', :email, 'test-only', 'MANAGER')
                RETURNING user_id
                """)
                .param("email", unique(suffix) + "@example.test")
                .query(Long.class)
                .single();
    }

    private long answeredAttention(Fixture fixture, long approverId, String answer) {
        return jdbc.sql("""
                INSERT INTO attention_requests
                    (case_id, work_item_id, reason_type, title, question, status,
                     resolved_by_user_id, answer_text, resolved_at)
                VALUES
                    (:caseId, :workItemId, 'AUTHORITY_REQUIRED', 'Approval needed',
                     'Approve this action?', 'ANSWERED', :approverId, :answer,
                     CURRENT_TIMESTAMP)
                RETURNING attention_request_id
                """)
                .param("caseId", fixture.caseId())
                .param("workItemId", fixture.workItemId())
                .param("approverId", approverId)
                .param("answer", answer)
                .query(Long.class)
                .single();
    }

    private long approvedGovernanceAction(long approverId) {
        return approvedGovernanceAction(approverId, "PURCHASE_ORDER", 1);
    }

    private long approvedGovernanceAction(
            long approverId, String resourceType, long resourceId) {
        long approvalId = jdbc.sql("""
                INSERT INTO governance_actions
                    (requested_by, action_type, resource_type, resource_id, payload,
                     required_role, status)
                VALUES
                    (:userId, 'TEST_APPROVAL', :resourceType, :resourceId, '{}'::jsonb,
                     'MANAGER', 'APPROVED')
                RETURNING governance_action_id
                """)
                .param("userId", approverId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .query(Long.class)
                .single();
        jdbc.sql("""
                INSERT INTO governance_decisions
                    (governance_action_id, decided_by, decision, reason)
                VALUES (:approvalId, :userId, 'APPROVE', 'Approved in test')
                """)
                .param("approvalId", approvalId)
                .param("userId", approverId)
                .update();
        return approvalId;
    }

    private long claim(long caseId) {
        return jdbc.sql("""
                INSERT INTO claims (case_id, subject_type, subject_ref, claim_text)
                VALUES (:caseId, 'STOCK', :subjectRef, 'Inventory claim')
                RETURNING claim_id
                """)
                .param("caseId", caseId)
                .param("subjectRef", unique("STOCK"))
                .query(Long.class)
                .single();
    }

    private String evidence(long caseId) {
        String evidenceRef = unique("EV");
        jdbc.sql("""
                INSERT INTO evidence (evidence_ref, case_id, source_type, title)
                VALUES (:evidenceRef, :caseId, 'API', 'Inventory evidence')
                """)
                .param("evidenceRef", evidenceRef)
                .param("caseId", caseId)
                .update();
        return evidenceRef;
    }

    private long claimEvidenceCount(long claimId, String evidenceRef, String relation) {
        return jdbc.sql("""
                SELECT count(*)
                FROM claim_evidence ce
                JOIN evidence e ON e.evidence_id=ce.evidence_id
                WHERE ce.claim_id=:claimId
                  AND e.evidence_ref=:evidenceRef
                  AND ce.relation=:relation
                """)
                .param("claimId", claimId)
                .param("evidenceRef", evidenceRef)
                .param("relation", relation)
                .query(Long.class)
                .single();
    }

    private EventAttribution eventAttribution(long eventId) {
        return jdbc.sql("""
                SELECT actor_type::text, user_id, case_id, work_item_id
                FROM events
                WHERE event_id=:eventId
                """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new EventAttribution(
                        rs.getString(1), nullableLong(rs, 2), nullableLong(rs, 3), nullableLong(rs, 4)))
                .single();
    }

    private Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private long eventCount(String eventType, String externalRef) {
        return jdbc.sql("SELECT count(*) FROM events WHERE event_type=:type AND external_ref=:externalRef")
                .param("type", eventType).param("externalRef", externalRef)
                .query(Long.class).single();
    }

    private String eventType(long eventId) {
        return jdbc.sql("SELECT event_type FROM events WHERE event_id=:id")
                .param("id", eventId).query(String.class).single();
    }

    private String eventPayloadValue(long eventId, String key) {
        return jdbc.sql("SELECT payload->>:key FROM events WHERE event_id=:id")
                .param("key", key)
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private EventDto eventFromHistory(String caseRef, long eventId) {
        return dispatcher.listEvents(caseRef).stream()
                .filter(event -> event.eventId() == eventId)
                .findFirst()
                .orElseThrow();
    }

    private CanonicalScope canonicalScope(long eventId) {
        return jdbc.sql("""
                SELECT payload->>'caseId', payload->>'case_id',
                       payload->>'caseRef', payload->>'case_ref',
                       payload->>'workItemId', payload->>'work_item_id',
                       payload->>'workItemRef', payload->>'work_item_ref'
                FROM events
                WHERE event_id=:eventId
                """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new CanonicalScope(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                .single();
    }

    private DependencyObservation dependencyObservation(long eventId, String workItemRef) {
        return jdbc.sql("""
                SELECT observation->>'workItemRef' AS work_item_ref,
                       observation->>'status' AS status,
                       observation->>'observedAt' AS observed_at,
                       (SELECT count(*) FROM jsonb_object_keys(observation)) AS property_count
                FROM events e
                CROSS JOIN LATERAL jsonb_array_elements(e.payload->'dependencyStates')
                    AS state(observation)
                WHERE e.event_id=:eventId
                  AND observation->>'workItemRef'=:workItemRef
                """)
                .param("eventId", eventId)
                .param("workItemRef", workItemRef)
                .query((rs, rowNum) -> new DependencyObservation(
                        rs.getString("work_item_ref"),
                        rs.getString("status"),
                        Instant.parse(rs.getString("observed_at")),
                        rs.getLong("property_count")))
                .single();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record Fixture(long caseId, String caseRef, long workItemId, String workItemRef,
                           long waitingId, String waitingRef, long agentId) {
    }

    private record DependencyObservation(String workItemRef, String status, Instant observedAt,
                                         long propertyCount) {
    }

    private record CanonicalScope(String caseId, String caseIdSnake,
                                  String caseRef, String caseRefSnake,
                                  String workItemId, String workItemIdSnake,
                                  String workItemRef, String workItemRefSnake) {
    }

    private record EventAttribution(String actorType, Long userId, Long caseId, Long workItemId) {
    }
}
