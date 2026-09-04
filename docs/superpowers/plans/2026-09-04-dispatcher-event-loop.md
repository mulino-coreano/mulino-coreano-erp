# Dispatcher Event Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/09_dispatcher_spec.md` so an incoming event deterministically satisfies matching waiting conditions, makes affected work items runnable, creates one auditable Run per runnable work item, and exposes event/dispatch REST APIs.

**Architecture:** Keep matching as a pure Java component and transaction orchestration in `DispatcherService`. Move Run creation and context reconstruction out of `InterfaceService` into `RunService` and `ContextSnapshotService`; PostgreSQL remains the source of truth for state transitions and audit links.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring JDBC `JdbcClient`, PostgreSQL 18, Flyway, JUnit 5.

**Spec:** `docs/09_dispatcher_spec.md`

## Global Constraints

- Event ingestion and all matching state transitions execute in one Spring transaction.
- A repeated `(event_type, external_ref)` must not duplicate Events, transitions, or Runs.
- `DONE` and `CANCELLED` work items never transition.
- A Run context is rebuilt from current database state at Run creation and contains `reconstructed_at`.
- The actual Claude/Codex invocation remains outside this implementation; a created `RUNNING` Run is the scheduling record.
- Existing untracked `docs/09_dispatcher_spec.md` is authoritative and must be preserved.

---

### Task 1: Event idempotency schema

**Files:**
- Modify: `database/ddl/07_case_management.sql`
- Create: `backend/src/main/resources/db/migration/V12__dispatcher_event_idempotency.sql`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherSchemaIntegrationTest.java`

**Interfaces:**
- Produces: nullable `events.external_ref VARCHAR(255)` and unique constraint `uk_events_type_external(event_type, external_ref)`.

- [x] **Step 1: Write a failing schema integration test**

```java
@Test
void eventsExposeExternalReferenceAndRejectDuplicates() {
    assertThat(columnExists("events", "external_ref")).isTrue();
    insertEvent("SUPPLIER_EMAIL_RECEIVED", "msg-schema-1");
    assertThatThrownBy(() -> insertEvent("SUPPLIER_EMAIL_RECEIVED", "msg-schema-1"))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [x] **Step 2: Run the test and confirm failure because `external_ref` is absent**

Run: `cd backend && ./gradlew test --tests '*DispatcherSchemaIntegrationTest' --no-daemon`

- [x] **Step 3: Add canonical DDL and Flyway migration**

```sql
ALTER TABLE events ADD COLUMN external_ref VARCHAR(255) NULL;
ALTER TABLE events ADD CONSTRAINT uk_events_type_external UNIQUE (event_type, external_ref);
```

- [x] **Step 4: Run the schema test and full baseline**

Run: `cd backend && ./gradlew test --no-daemon`

### Task 2: Pure waiting-condition matcher

**Files:**
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/WaitingCondition.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/DispatchEvent.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/WaitingConditionMatcher.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/WaitingConditionMatcherTest.java`

**Interfaces:**
- Consumes: `WaitingCondition(type, payload)` and `DispatchEvent(eventType, caseId, workItemId, payload, occurredAt)`.
- Produces: `boolean WaitingConditionMatcher.matches(WaitingCondition condition, DispatchEvent event, Instant now)`.

- [x] **Step 1: Write failing table-driven tests for all six condition types**

```java
@ParameterizedTest
@MethodSource("matchingExamples")
void matchesDocumentedCondition(String type, String conditionJson, String eventType,
                                String eventJson, boolean expected) {
    assertThat(matcher.matches(condition(type, conditionJson),
            event(eventType, eventJson), NOW)).isEqualTo(expected);
}
```

Examples cover `SUPPLIER_REPLY`, `EMAIL_SENT`, `APPROVAL`, `SCHEDULED_TIME`, `EXTERNAL_DATA`, and `DEPENDENCY_DONE`, including mismatches.

- [x] **Step 2: Run and confirm failure because matcher types do not exist**

Run: `cd backend && ./gradlew test --tests '*WaitingConditionMatcherTest' --no-daemon`

- [x] **Step 3: Implement literal matching rules from spec §2-1**

```java
public boolean matches(WaitingCondition condition, DispatchEvent event, Instant now) {
    return switch (condition.type()) {
        case "SUPPLIER_REPLY" -> supplierReply(condition.payload(), event);
        case "EMAIL_SENT" -> emailSent(condition.payload(), event);
        case "APPROVAL" -> approval(condition.payload(), event);
        case "SCHEDULED_TIME" -> scheduledTime(condition.payload(), now);
        case "EXTERNAL_DATA" -> externalData(condition.payload(), event);
        case "DEPENDENCY_DONE" -> dependencyDone(condition.payload(), event);
        default -> false;
    };
}
```

- [x] **Step 4: Run matcher tests**

Run: `cd backend && ./gradlew test --tests '*WaitingConditionMatcherTest' --no-daemon`

### Task 3: Run creation and always-fresh context

**Files:**
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/ContextSnapshotService.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/RunService.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/InterfaceService.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/RunServiceIntegrationTest.java`

**Interfaces:**
- Produces: `RunDto RunService.createRun(CreateRunRequest request, Long triggerEventId)`.
- Produces: `Map<String,Object> ContextSnapshotService.build(String caseRef)` with `objective`, `obligation`, `organizational`, `business`, `epistemic`, `control`, `reconstructed_at`, and `stale=false`.
- Produces: `boolean RunService.hasActiveRun(long workItemId)`.

- [x] **Step 1: Write failing tests for trigger audit, reconstruction timestamp, current-state refresh, and real business references**

```java
@Test
void createRunRecordsTriggerAndFreshContext() {
    RunDto run = runService.createRun(request, eventId);
    assertThat(run.status()).isEqualTo("RUNNING");
    assertThat(runRow(run.runId()).triggerEventId()).isEqualTo(eventId);
    assertThat(snapshot(run.runId()).path("reconstructed_at").isTextual()).isTrue();
    assertThat(snapshot(run.runId()).path("business").toString()).doesNotContain("pending");
}
```

- [x] **Step 2: Run and confirm failure because `RunService` does not exist**

Run: `cd backend && ./gradlew test --tests '*RunServiceIntegrationTest' --no-daemon`

- [x] **Step 3: Implement context builder and move Run persistence from `InterfaceService`**

The business layer aggregates `work_items.metadata.businessRef` entries as real ERP reference indexes; control remains the Phase-5 policy source reference required by spec §4-2.

- [x] **Step 4: Implement one retry; after a second reconstruction failure mark Run `FAILED`, set `finished_at`, and persist the last successful snapshot with `stale=true`**

- [x] **Step 5: Run Run tests and full tests**

Run: `cd backend && ./gradlew test --no-daemon`

### Task 4: Transactional Dispatcher service

**Files:**
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/CreateEventRequest.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/EventDispatchResponse.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/EventDto.java`
- Create: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/DispatcherService.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherIntegrationTest.java`

**Interfaces:**
- Produces: `EventDispatchResponse DispatcherService.ingest(CreateEventRequest request)`.
- Produces: `EventDispatchResponse DispatcherService.dispatchScheduled()`.
- Produces: `List<EventDto> DispatcherService.listEvents(String caseRef)`.

- [x] **Step 1: Write failing integration tests for spec T1-T5 and T9**

```java
@Test
void supplierReplyMakesWaitingWorkItemReadyAndSchedulesAuditableRun() {
    EventDispatchResponse result = dispatcher.ingest(supplierReplyRequest());
    assertThat(result.satisfiedWaiting()).containsExactly(waitingRef);
    assertThat(result.readyWorkItems()).containsExactly(workItemRef);
    assertThat(result.scheduledRuns()).hasSize(1);
    assertDatabaseTransitionAndAuditLinks(result.eventId());
}
```

- [x] **Step 2: Run and confirm failure because `DispatcherService` does not exist**

Run: `cd backend && ./gradlew test --tests '*DispatcherIntegrationTest' --no-daemon`

- [x] **Step 3: Implement event insert/deduplication, candidate loading, conditional transitions, and one Run per newly READY work item**

Use guarded SQL updates (`status='ACTIVE' AND resolved_by_event_id IS NULL`, `work_items.status='WAITING'`) and a set of work-item IDs to prevent duplicate scheduling.

- [x] **Step 4: Implement source-specific `DISPATCH_REQUESTED`/`DISPATCH_SWEEP_TRIGGERED` Events so every transition has a truthful audit Event**

- [x] **Step 5: Run Dispatcher tests and full tests**

Run: `cd backend && ./gradlew test --no-daemon`

### Task 5: REST contract

**Files:**
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/InterfaceController.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherControllerIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/v1/events` returning HTTP 202 and `EventDispatchResponse`.
- Produces: `POST /api/v1/dispatch` returning HTTP 202 and `EventDispatchResponse`.
- Produces: `GET /api/v1/events?caseRef=` returning `List<EventDto>`.

- [x] **Step 1: Write failing HTTP integration tests for all three endpoints**

```java
mockMvc.perform(post("/api/v1/events").contentType(APPLICATION_JSON).content(requestJson))
       .andExpect(status().isAccepted())
       .andExpect(jsonPath("$.eventId").isNumber());
```

- [x] **Step 2: Run and confirm 404 failures**

Run: `cd backend && ./gradlew test --tests '*DispatcherControllerIntegrationTest' --no-daemon`

- [x] **Step 3: Add controller methods delegating to `DispatcherService`**

- [x] **Step 4: Run all tests and inspect migration status**

Run: `cd backend && ./gradlew test --no-daemon`

### Task 6: Contract audit and documentation alignment

**Files:**
- Modify if required: `docs/09_dispatcher_spec.md`
- Modify if required: `docs/08_interface_overview.md`

- [x] **Step 1: Verify acceptance criteria T1-T9 against named tests**
- [x] **Step 2: Verify no `erp_link: pending` remains in production code**

Run: `rg -n 'erp_link|pending' backend/src/main/java`

- [x] **Step 3: Run final build**

Run: `cd backend && ./gradlew clean test --no-daemon`

- [x] **Step 4: Review `git diff` for unrelated or generated changes**

---

### Task 7: Independent-review schema hardening

**Files:**
- Modify: `database/ddl/07_case_management.sql`
- Modify: `database/ddl/09_case_fks.sql`
- Create: `backend/src/main/resources/db/migration/V15__dispatcher_integrity_hardening.sql`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherSchemaIntegrationTest.java`

**Interfaces:**
- Produces: database-enforced Event/Run `(work_item_id, case_id)` coherence.
- Produces: append-only Event protection for `TRUNCATE` as well as `UPDATE` and `DELETE`.

- [x] **Step 1: Add failing direct-SQL tests for cross-Case Events/Runs and Event TRUNCATE**
- [x] **Step 2: Confirm the tests fail against V14**
- [x] **Step 3: Add canonical constraints/triggers and Flyway V15**
- [x] **Step 4: Apply V15 with the schema-owner migration account and rerun schema tests**

### Task 8: Atomic Run eligibility and coherent context snapshots

**Files:**
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/RunService.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/ContextSnapshotService.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/CreateRunRequest.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/InterfaceController.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/RunSchedulingIntegrationTest.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/RunServiceIntegrationTest.java`

**Interfaces:**
- Work-Item Runs require a locked `READY` Work Item, its active assigned agent, no user assignment, and runtime `CLAUDE|CODEX`.
- Context construction reads all six layers from one PostgreSQL statement snapshot.
- Reconstruction fallback selects the newest successfully reconstructed Case snapshot, independent of later execution outcome.

- [x] **Step 1: Add failing eligibility, runtime, and fallback-order tests**
- [x] **Step 2: Confirm each failure is caused by the reviewed defect**
- [x] **Step 3: Implement locked target resolution and request validation**
- [x] **Step 4: Replace multi-statement context reads with one aggregate query**
- [x] **Step 5: Run Run/context tests**

### Task 9: Selector identity safety

**Files:**
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/WaitingConditionMatcher.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/WaitingConditionMatcherTest.java`

**Interfaces:**
- Approval matching requires a matching `attention_request_id` or `approval_id`; a decision value is an additional constraint, never global identity.
- EMAIL_SENT requires every selector present in the condition to match.
- EXTERNAL_DATA accepts the documented `THIRD_PARTY_*_RECEIVED` event class while still requiring source equality.

- [x] **Step 1: Add failing cross-scope and multi-selector matcher cases**
- [x] **Step 2: Confirm false-positive/false-negative behavior**
- [x] **Step 3: Implement identity-first matching rules**
- [x] **Step 4: Run matcher tests**

### Task 10: Authoritative event routing and truthful audit outcomes

**Files:**
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/DispatcherService.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/EventDispatchResponse.java`
- Modify: `backend/src/main/java/com/mulinocoreano/backend/interfacepackage/EventDto.java` if association metadata is required
- Modify: `docs/09_dispatcher_spec.md`
- Modify: `docs/08_interface_overview.md`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherIntegrationTest.java`
- Test: `backend/src/test/java/com/mulinocoreano/backend/interfacepackage/DispatcherControllerIntegrationTest.java`

**Interfaces:**
- `WORK_ITEM_STATUS_CHANGED` resolves a real source Work Item, verifies terminal status, and finds dependent target waits by `dependent_wi_ref`.
- `CHANGE_REQUEST_APPROVED` resolves a completed Attention or approved governance action and derives its human actor before dispatch.
- Manual and monitor sweeps record truthful source-specific dispatch Events and never rewrite an Event's direct scope during filtered reads.
- Failed context reconstruction is excluded from `scheduledRuns`, exposed as `failedRuns`, and opens one `MATERIAL_EXCEPTION` attention.
- Event-carried claim/evidence associations are validated and persisted transactionally.
- Non-object condition payloads fail closed instead of aborting unrelated candidates.

- [x] **Step 1: Add failing integration/API tests for each reviewed scenario**
- [x] **Step 2: Confirm the reviewed false positives, false negatives, and audit ambiguity**
- [x] **Step 3: Implement authoritative event preparation and source-target routing**
- [x] **Step 4: Implement truthful dispatch/audit projection and failure outcomes**
- [x] **Step 5: Implement validated claim/evidence linking required by spec §2-3**
- [x] **Step 6: Run Dispatcher/API tests and the full suite**
