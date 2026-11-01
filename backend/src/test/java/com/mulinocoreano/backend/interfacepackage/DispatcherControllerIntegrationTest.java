package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DispatcherControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void postEventsAcceptsAndDispatchesEvent() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":44}");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"%s",
                                  "caseRef":"%s",
                                  "payload":{"supplierId":44}
                                }
                                """.formatted(unique("msg"), fixture.caseRef())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNumber())
                .andExpect(jsonPath("$.satisfiedWaiting[0]").value(fixture.waitingRef()))
                .andExpect(jsonPath("$.readyWorkItems[0]").value(fixture.workItemRef()))
                .andExpect(jsonPath("$.scheduledRuns", hasSize(1)));
    }

    @Test
    void postRunsReturnsConflictWhenWorkItemAlreadyHasRunningRun() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":45}");
        jdbc.sql("UPDATE work_items SET status='READY' WHERE work_item_ref=:workItemRef")
                .param("workItemRef", fixture.workItemRef())
                .update();
        String request = """
                {
                  "agentKey":"%s",
                  "caseRef":"%s",
                  "workItemRef":"%s",
                  "runtime":"CODEX"
                }
                """.formatted(agentKey(fixture.workItemRef()), fixture.caseRef(), fixture.workItemRef());

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict());

        assertThat(runCountForWorkItem(fixture.workItemRef())).isEqualTo(1);
    }

    @Test
    void postRunsRejectsWorkItemFromAnotherCaseBeforeInsert() throws Exception {
        Fixture requestedCase = fixture("SUPPLIER_REPLY", "{\"supplier_id\":46}");
        Fixture otherCase = fixture("SUPPLIER_REPLY", "{\"supplier_id\":47}");

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentKey":"%s",
                                  "caseRef":"%s",
                                  "workItemRef":"%s",
                                  "runtime":"CODEX"
                                }
                                """.formatted(agentKey(requestedCase.workItemRef()),
                                        requestedCase.caseRef(), otherCase.workItemRef())))
                .andExpect(status().isBadRequest());

        assertThat(runCountForWorkItem(otherCase.workItemRef())).isZero();
    }

    @Test
    void postDispatchAcceptsAndReevaluatesDueTimers() throws Exception {
        Fixture fixture = fixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(5, ChronoUnit.MINUTES) + "\"}");

        mockMvc.perform(post("/api/v1/dispatch"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNumber());

        assertThat(jdbc.sql("SELECT status::text FROM waiting_conditions WHERE waiting_condition_id=:id")
                .param("id", fixture.waitingId()).query(String.class).single())
                .isEqualTo("SATISFIED");
    }

    @Test
    void getEventsFiltersAuditHistoryByCaseReference() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":55}");
        jdbc.sql("""
                INSERT INTO events (event_type, external_ref, case_id, payload)
                VALUES ('SUPPLIER_EMAIL_RECEIVED', :externalRef, :caseId, '{"supplierId":55}'::jsonb)
                """)
                .param("externalRef", unique("audit"))
                .param("caseId", fixture.caseId())
                .update();

        mockMvc.perform(get("/api/v1/events").param("caseRef", fixture.caseRef()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].caseRef").value(fixture.caseRef()))
                .andExpect(jsonPath("$[0].eventType").value("SUPPLIER_EMAIL_RECEIVED"));
    }

    @Test
    void getEventsIncludesGlobalScheduledEventThatResolvedCaseWait() throws Exception {
        Fixture fixture = fixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(10, ChronoUnit.MINUTES) + "\"}");
        mockMvc.perform(post("/api/v1/dispatch"))
                .andExpect(status().isAccepted());

        String auditHistory = mockMvc.perform(
                        get("/api/v1/events").param("caseRef", fixture.caseRef()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(auditHistory).contains("DISPATCH_REQUESTED");
    }

    @Test
    void getEventsRejectsAnExplicitlyBlankCaseFilter() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("caseRef", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postEventsReturnsConflictWhenIdempotencyKeyContentChanges() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":66}");
        String externalRef = unique("msg");
        String first = """
                {"eventType":"SUPPLIER_EMAIL_RECEIVED","externalRef":"%s",\
                 "caseRef":"%s","payload":{"supplierId":66}}
                """.formatted(externalRef, fixture.caseRef());
        String conflicting = """
                {"eventType":"SUPPLIER_EMAIL_RECEIVED","externalRef":"%s",\
                 "caseRef":"%s","payload":{"supplierId":67}}
                """.formatted(externalRef, fixture.caseRef());

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON).content(conflicting))
                .andExpect(status().isConflict());
    }

    @Test
    void postEventsRejectsBlankExternalReferenceAsBadRequest() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":67}");
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"   ",
                                  "caseRef":"%s",
                                  "payload":{"supplierId":67}
                                }
                                """.formatted(fixture.caseRef())))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsRejectsUnknownCaseReferenceAsBadRequest() throws Exception {
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"%s",
                                  "caseRef":"CASE-UNKNOWN",
                                  "payload":{}
                                }
                                """.formatted(unique("msg"))))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsRejectsUnknownWorkItemReferenceAsBadRequest() throws Exception {
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"%s",
                                  "workItemRef":"WI-UNKNOWN",
                                  "payload":{}
                                }
                                """.formatted(unique("msg"))))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsRejectsWorkItemFromAnotherCaseAsBadRequest() throws Exception {
        Fixture requestedCase = fixture("SUPPLIER_REPLY", "{\"supplier_id\":69}");
        Fixture otherCase = fixture("SUPPLIER_REPLY", "{\"supplier_id\":70}");
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"%s",
                                  "caseRef":"%s",
                                  "workItemRef":"%s",
                                  "payload":{}
                                }
                                """.formatted(
                                        unique("msg"), requestedCase.caseRef(), otherCase.workItemRef())))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsRejectsPayloadIdentityThatContradictsResolvedScope() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":71}");
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                                  "externalRef":"%s",
                                  "caseRef":"%s",
                                  "workItemRef":"%s",
                                  "payload":{"work_item_id":%d}
                                }
                                """.formatted(unique("msg"), fixture.caseRef(),
                                        fixture.workItemRef(), Long.MAX_VALUE)))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsRejectsEventTypeLongerThanSchemaLimit() throws Exception {
        long eventsBefore = eventCount("SUPPLIER_EMAIL_RECEIVED");

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType":"%s",
                                  "externalRef":"%s",
                                  "payload":{}
                                }
                                """.formatted("E".repeat(101), unique("msg"))))
                .andExpect(status().isBadRequest());

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED")).isEqualTo(eventsBefore);
    }

    @Test
    void postEventsTrimsExternalReferenceAndReturnsSameEventForIdenticalDuplicate() throws Exception {
        Fixture fixture = fixture("SUPPLIER_REPLY", "{\"supplier_id\":68}");
        String externalRef = unique("msg");
        String requestTemplate = """
                {
                  "eventType":"SUPPLIER_EMAIL_RECEIVED",
                  "externalRef":"%s",
                  "caseRef":"%s",
                  "payload":{"supplierId":68}
                }
                """;

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestTemplate.formatted("  " + externalRef + "  ", fixture.caseRef())))
                .andExpect(status().isAccepted());

        long eventId = eventId("SUPPLIER_EMAIL_RECEIVED", externalRef);

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestTemplate.formatted(externalRef, fixture.caseRef())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.satisfiedWaiting", hasSize(0)))
                .andExpect(jsonPath("$.readyWorkItems", hasSize(0)))
                .andExpect(jsonPath("$.scheduledRuns", hasSize(0)));

        assertThat(eventCount("SUPPLIER_EMAIL_RECEIVED", externalRef)).isEqualTo(1);
        assertThat(runCountForWorkItem(fixture.workItemRef())).isEqualTo(1);
    }

    @Test
    void getMonitorSweepsDueScheduledWaitsBeforeReturningMonitorDto() throws Exception {
        Fixture fixture = fixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().minus(5, ChronoUnit.MINUTES) + "\"}");

        mockMvc.perform(get("/api/v1/monitor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workItemsReady").isNumber())
                .andExpect(jsonPath("$.workItemsWaiting").isNumber());

        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("SATISFIED");
        assertThat(workItemStatus(fixture.workItemRef())).isEqualTo("READY");
        long eventId = waitingResolvedBy(fixture.waitingId());
        assertThat(eventType(eventId)).isEqualTo("DISPATCH_SWEEP_TRIGGERED");
        assertThat(eventPayloadValue(eventId, "source")).isEqualTo("MONITOR");
        assertThat(runCountForEventAndWorkItem(eventId, fixture.workItemRef())).isEqualTo(1);
    }

    @Test
    void getMonitorDoesNotCreateEventWhenNoScheduledWaitIsActionable() throws Exception {
        Fixture fixture = fixture("SCHEDULED_TIME",
                "{\"due_at\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}");
        long eventsBefore = eventCount("DISPATCH_SWEEP_TRIGGERED");

        mockMvc.perform(get("/api/v1/monitor"))
                .andExpect(status().isOk());

        assertThat(eventCount("DISPATCH_SWEEP_TRIGGERED")).isEqualTo(eventsBefore);
        assertThat(waitingStatus(fixture.waitingId())).isEqualTo("ACTIVE");
    }

    private Fixture fixture(String conditionType, String conditionPayload) {
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:key, 'HTTP Dispatcher Agent') RETURNING agent_id
                """)
                .param("key", unique("AGENT"))
                .query(Long.class)
                .single();
        String caseRef = unique("CASE");
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:ref, 'HTTP dispatcher case', 'Exercise dispatcher endpoints', 'ACT')
                RETURNING case_id
                """)
                .param("ref", caseRef)
                .query(Long.class)
                .single();
        String workItemRef = unique("WI");
        long workItemId = jdbc.sql("""
                INSERT INTO work_items
                    (work_item_ref, case_id, title, status, assigned_agent_id,
                     metadata)
                VALUES
                    (:ref, :caseId, 'HTTP dispatcher work', 'WAITING', :agentId,
                     '{"businessRef":{"type":"supplier","ref":"SUP-44"}}'::jsonb)
                RETURNING work_item_id
                """)
                .param("ref", workItemRef)
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();
        String waitingRef = unique("WAIT");
        long waitingId = jdbc.sql("""
                INSERT INTO waiting_conditions
                    (waiting_ref, work_item_id, condition_type, condition_payload, reason)
                VALUES
                    (:ref, :workItemId, :type::waiting_condition_type,
                     CAST(:payload AS jsonb), 'HTTP dispatcher test')
                RETURNING waiting_condition_id
                """)
                .param("ref", waitingRef)
                .param("workItemId", workItemId)
                .param("type", conditionType)
                .param("payload", conditionPayload)
                .query(Long.class)
                .single();
        return new Fixture(caseId, caseRef, workItemRef, waitingId, waitingRef);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long eventCount(String eventType) {
        return jdbc.sql("SELECT count(*) FROM events WHERE event_type=:eventType")
                .param("eventType", eventType)
                .query(Long.class)
                .single();
    }

    private long eventCount(String eventType, String externalRef) {
        return jdbc.sql("""
                SELECT count(*) FROM events
                WHERE event_type=:eventType AND external_ref=:externalRef
                """)
                .param("eventType", eventType)
                .param("externalRef", externalRef)
                .query(Long.class)
                .single();
    }

    private long eventId(String eventType, String externalRef) {
        return jdbc.sql("""
                SELECT event_id FROM events
                WHERE event_type=:eventType AND external_ref=:externalRef
                """)
                .param("eventType", eventType)
                .param("externalRef", externalRef)
                .query(Long.class)
                .single();
    }

    private String waitingStatus(long waitingId) {
        return jdbc.sql("SELECT status::text FROM waiting_conditions WHERE waiting_condition_id=:id")
                .param("id", waitingId)
                .query(String.class)
                .single();
    }

    private long waitingResolvedBy(long waitingId) {
        return jdbc.sql("SELECT resolved_by_event_id FROM waiting_conditions WHERE waiting_condition_id=:id")
                .param("id", waitingId)
                .query(Long.class)
                .single();
    }

    private String workItemStatus(String workItemRef) {
        return jdbc.sql("SELECT status::text FROM work_items WHERE work_item_ref=:workItemRef")
                .param("workItemRef", workItemRef)
                .query(String.class)
                .single();
    }

    private String eventType(long eventId) {
        return jdbc.sql("SELECT event_type FROM events WHERE event_id=:eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private String eventPayloadValue(long eventId, String key) {
        return jdbc.sql("SELECT payload->>:key FROM events WHERE event_id=:eventId")
                .param("key", key)
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private long runCountForWorkItem(String workItemRef) {
        return jdbc.sql("""
                SELECT count(*)
                FROM runs r
                JOIN work_items wi ON wi.work_item_id=r.work_item_id
                WHERE wi.work_item_ref=:workItemRef
                """)
                .param("workItemRef", workItemRef)
                .query(Long.class)
                .single();
    }

    private String agentKey(String workItemRef) {
        return jdbc.sql("""
                SELECT a.agent_key
                FROM work_items wi
                JOIN agents a ON a.agent_id=wi.assigned_agent_id
                WHERE wi.work_item_ref=:workItemRef
                """)
                .param("workItemRef", workItemRef)
                .query(String.class)
                .single();
    }

    private long runCountForEventAndWorkItem(long eventId, String workItemRef) {
        return jdbc.sql("""
                SELECT count(*)
                FROM runs r
                JOIN work_items wi ON wi.work_item_id=r.work_item_id
                WHERE r.trigger_event_id=:eventId AND wi.work_item_ref=:workItemRef
                """)
                .param("eventId", eventId)
                .param("workItemRef", workItemRef)
                .query(Long.class)
                .single();
    }

    private record Fixture(long caseId, String caseRef, String workItemRef,
                           long waitingId, String waitingRef) {
    }
}
