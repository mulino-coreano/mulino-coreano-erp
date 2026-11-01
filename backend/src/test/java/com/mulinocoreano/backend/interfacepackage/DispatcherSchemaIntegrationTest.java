package com.mulinocoreano.backend.interfacepackage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class DispatcherSchemaIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void eventsExposeExternalReferenceAndRejectDuplicates() {
        assertThat(columnExists("events", "external_ref")).isTrue();

        String externalRef = "msg-schema-" + UUID.randomUUID();
        insertEvent("SUPPLIER_EMAIL_RECEIVED", externalRef);

        assertThatThrownBy(() -> insertEvent("SUPPLIER_EMAIL_RECEIVED", externalRef))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventsRejectUpdatesBecauseTheyAreAppendOnly() {
        long eventId = insertEvent("SUPPLIER_EMAIL_RECEIVED", "msg-update-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("UPDATE events SET event_type='ALTERED' WHERE event_id=:eventId")
                .param("eventId", eventId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("events is append-only");
    }

    @Test
    void eventsRejectDeletesBecauseTheyAreAppendOnly() {
        long eventId = insertEvent("SUPPLIER_EMAIL_RECEIVED", "msg-delete-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM events WHERE event_id=:eventId")
                .param("eventId", eventId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("events is append-only");
    }

    @Test
    void eventCannotReferenceWorkItemFromDifferentCase() {
        long eventCaseId = insertCase("Event scope case");
        long workItemCaseId = insertCase("Event work item case");
        long workItemId = insertWorkItem(workItemCaseId, "Event scope work item");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO events (event_type, external_ref, case_id, work_item_id)
                VALUES ('WORK_ITEM_STATUS_CHANGED', :externalRef, :caseId, :workItemId)
                """)
                .param("externalRef", "msg-event-scope-" + UUID.randomUUID())
                .param("caseId", eventCaseId)
                .param("workItemId", workItemId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runCannotReferenceWorkItemFromDifferentCase() {
        long agentId = insertAgent("Run scope test");
        long runCaseId = insertCase("Run scope case");
        long workItemCaseId = insertCase("Run work item case");
        long workItemId = insertWorkItem(workItemCaseId, "Run scope work item");

        assertThatThrownBy(() -> insertRunningRun(
                "RUN-" + shortId(), agentId, runCaseId, workItemId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventWithWorkItemMustReferenceCase() {
        long caseId = insertCase("Event required case");
        long workItemId = insertWorkItem(caseId, "Event required case work item");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO events (event_type, external_ref, work_item_id)
                VALUES ('WORK_ITEM_STATUS_CHANGED', :externalRef, :workItemId)
                """)
                .param("externalRef", "msg-event-case-" + UUID.randomUUID())
                .param("workItemId", workItemId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventsRejectTruncatesBecauseTheyAreAppendOnly() {
        insertEvent("SUPPLIER_EMAIL_RECEIVED", "msg-truncate-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.sql("TRUNCATE TABLE events CASCADE").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("events is append-only");
    }

    @Test
    void workItemCannotHaveTwoRunningSchedulingRecords() {
        long agentId = jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:key, 'Run uniqueness test') RETURNING agent_id
                """)
                .param("key", "AGENT-" + shortId())
                .query(Long.class)
                .single();
        long caseId = jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:ref, 'Run uniqueness case', 'Prevent duplicate scheduling', 'ACT')
                RETURNING case_id
                """)
                .param("ref", "CASE-" + shortId())
                .query(Long.class)
                .single();
        long workItemId = jdbc.sql("""
                INSERT INTO work_items (work_item_ref, case_id, title, status, assigned_agent_id)
                VALUES (:ref, :caseId, 'Run uniqueness work', 'READY', :agentId)
                RETURNING work_item_id
                """)
                .param("ref", "WI-" + shortId())
                .param("caseId", caseId)
                .param("agentId", agentId)
                .query(Long.class)
                .single();

        insertRunningRun("RUN-" + shortId(), agentId, caseId, workItemId);

        assertThatThrownBy(() -> insertRunningRun(
                "RUN-" + shortId(), agentId, caseId, workItemId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean columnExists(String table, String column) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = :table
                      AND column_name = :column
                )
                """)
                .param("table", table)
                .param("column", column)
                .query(Boolean.class)
                .single();
    }

    private long insertAgent(String displayName) {
        return jdbc.sql("""
                INSERT INTO agents (agent_key, display_name)
                VALUES (:key, :displayName)
                RETURNING agent_id
                """)
                .param("key", "AGENT-" + shortId())
                .param("displayName", displayName)
                .query(Long.class)
                .single();
    }

    private long insertCase(String title) {
        return jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type)
                VALUES (:ref, :title, 'Dispatcher schema integrity test', 'ACT')
                RETURNING case_id
                """)
                .param("ref", "CASE-" + shortId())
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private long insertWorkItem(long caseId, String title) {
        return jdbc.sql("""
                INSERT INTO work_items (work_item_ref, case_id, title, status)
                VALUES (:ref, :caseId, :title, 'READY')
                RETURNING work_item_id
                """)
                .param("ref", "WI-" + shortId())
                .param("caseId", caseId)
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private long insertEvent(String eventType, String externalRef) {
        return jdbc.sql("""
                INSERT INTO events (event_type, external_ref)
                VALUES (:type, :externalRef)
                RETURNING event_id
                """)
                .param("type", eventType)
                .param("externalRef", externalRef)
                .query(Long.class)
                .single();
    }

    private void insertRunningRun(String runRef, long agentId, long caseId, long workItemId) {
        jdbc.sql("""
                INSERT INTO runs (run_ref, agent_id, case_id, work_item_id, runtime, status)
                VALUES (:runRef, :agentId, :caseId, :workItemId, 'CODEX', 'RUNNING')
                """)
                .param("runRef", runRef)
                .param("agentId", agentId)
                .param("caseId", caseId)
                .param("workItemId", workItemId)
                .update();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
