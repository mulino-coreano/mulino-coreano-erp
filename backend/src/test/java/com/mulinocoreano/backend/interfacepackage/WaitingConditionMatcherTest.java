package com.mulinocoreano.backend.interfacepackage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class WaitingConditionMatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final WaitingConditionMatcher matcher = new WaitingConditionMatcher();

    @ParameterizedTest(name = "{0}: {2} -> {4}")
    @MethodSource("matchingExamples")
    void matchesDocumentedCondition(String type, String conditionJson, String eventType,
                                    String eventJson, boolean expected) throws JacksonException {
        assertThat(matcher.matches(condition(type, conditionJson),
                event(eventType, eventJson), NOW)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectorSafetyExamples")
    void matchesSelectorsSafely(String scenario, String type, String conditionJson,
                                String eventType, String eventJson, boolean expected)
            throws JacksonException {
        assertThat(matcher.matches(condition(type, conditionJson),
                event(eventType, eventJson), NOW)).isEqualTo(expected);
    }

    @Test
    void malformedNumericSelectorFailsClosed() {
        WaitingCondition condition = new WaitingCondition(
                "SUPPLIER_REPLY", Map.of("supplier_id", Double.NaN));
        DispatchEvent event = new DispatchEvent(
                "SUPPLIER_EMAIL_RECEIVED", null, null,
                Map.of("supplier_id", Double.NaN), NOW);

        assertThat(matcher.matches(condition, event, NOW)).isFalse();
    }

    private static Stream<Arguments> matchingExamples() {
        return Stream.of(
                Arguments.of("SUPPLIER_REPLY", "{\"supplier_id\":42}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"supplierId\":42}", true),
                Arguments.of("SUPPLIER_REPLY", "{\"poRef\":\"PO-104\"}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"po_ref\":\"PO-104\"}", true),
                Arguments.of("SUPPLIER_REPLY", "{\"supplier_id\":42}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"supplier_id\":43}", false),
                Arguments.of("SUPPLIER_REPLY", "{\"supplier_id\":42}",
                        "EMAIL_SENT", "{\"supplier_id\":42}", false),
                Arguments.of("SUPPLIER_REPLY", "{}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"supplier_id\":42}", false),

                Arguments.of("EMAIL_SENT", "{\"case_id\":101}",
                        "EMAIL_SENT", "{\"caseId\":101}", true),
                Arguments.of("EMAIL_SENT", "{\"workItemId\":202}",
                        "EMAIL_SENT", "{\"work_item_id\":202}", true),
                Arguments.of("EMAIL_SENT", "{\"work_item_id\":202}",
                        "EMAIL_SENT", "{\"work_item_id\":203}", false),
                Arguments.of("EMAIL_SENT", "{\"case_id\":101}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"case_id\":101}", false),

                Arguments.of("APPROVAL", "{\"attention_request_id\":91}",
                        "CHANGE_REQUEST_APPROVED", "{\"attentionRequestId\":91}", true),
                Arguments.of("APPROVAL", "{\"expectedDecision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED", "{\"decision\":\"APPROVED\"}", false),
                Arguments.of("APPROVAL", "{\"attention_request_id\":91}",
                        "CHANGE_REQUEST_APPROVED", "{\"attention_request_id\":92}", false),
                Arguments.of("APPROVAL", "{\"decision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_REJECTED", "{\"decision\":\"APPROVED\"}", false),

                Arguments.of("SCHEDULED_TIME", "{\"due_at\":\"2026-09-04T11:59:59Z\"}",
                        "SCHEDULED_TIME_DUE", "{}", true),
                Arguments.of("SCHEDULED_TIME", "{\"dueAt\":\"2026-09-04T12:00:00Z\"}",
                        "UNRELATED_EVENT", "{}", true),
                Arguments.of("SCHEDULED_TIME", "{\"due_at\":\"2026-09-04T12:00:01Z\"}",
                        "SCHEDULED_TIME_DUE", "{}", false),
                Arguments.of("SCHEDULED_TIME", "{\"due_at\":\"not-an-instant\"}",
                        "SCHEDULED_TIME_DUE", "{}", false),

                Arguments.of("EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"source\":\"3PL\"}", true),
                Arguments.of("EXTERNAL_DATA", "{\"expectedSource\":\"warehouse-a\"}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"expected_source\":\"warehouse-a\"}", true),
                Arguments.of("EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"source\":\"ERP\"}", false),
                Arguments.of("EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}",
                        "SUPPLIER_EMAIL_RECEIVED", "{\"source\":\"3PL\"}", false),

                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-12\"}",
                        "WORK_ITEM_STATUS_CHANGED", "{\"workItemRef\":\"WI-12\",\"status\":\"DONE\"}", true),
                Arguments.of("DEPENDENCY_DONE", "{\"dependentWiRef\":\"WI-13\"}",
                        "WORK_ITEM_STATUS_CHANGED", "{\"dependent_wi_ref\":\"WI-13\",\"status\":\"CANCELLED\"}", true),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-14\"}",
                        "DISPATCH_REQUESTED", "{\"work_item_ref\":\"WI-14\",\"status\":\"DONE\"}", true),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-15\"}",
                        "DISPATCH_SWEEP_TRIGGERED", "{\"work_item_ref\":\"WI-15\",\"status\":\"DONE\"}", true),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-14\"}",
                        "SCHEDULED_TIME_DUE", "{\"work_item_ref\":\"WI-14\",\"status\":\"DONE\"}", false),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-12\"}",
                        "WORK_ITEM_STATUS_CHANGED", "{\"work_item_ref\":\"WI-12\",\"status\":\"IN_PROGRESS\"}", false),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-12\"}",
                        "WORK_ITEM_STATUS_CHANGED", "{\"work_item_ref\":\"WI-99\",\"status\":\"DONE\"}", false),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-12\"}",
                        "INVENTORY_CHANGED", "{\"work_item_ref\":\"WI-12\",\"status\":\"DONE\"}", false),
                Arguments.of("DEPENDENCY_DONE", "{\"dependent_wi_ref\":\"WI-12\"}",
                        null, "{\"work_item_ref\":\"WI-12\",\"status\":\"DONE\"}", false),

                Arguments.of("UNKNOWN", "{}", "SUPPLIER_EMAIL_RECEIVED", "{}", false));
    }

    private static Stream<Arguments> selectorSafetyExamples() {
        return Stream.of(
                Arguments.of("APPROVAL rejects decision equality without an identity",
                        "APPROVAL", "{\"decision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED", "{\"decision\":\"APPROVED\"}", false),
                Arguments.of("APPROVAL rejects a matching decision when attention identity differs",
                        "APPROVAL", "{\"attention_request_id\":91,\"expected_decision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED",
                        "{\"attentionRequestId\":92,\"decision\":\"APPROVED\"}", false),
                Arguments.of("APPROVAL requires a declared decision after attention identity matches",
                        "APPROVAL", "{\"attentionRequestId\":91,\"expectedDecision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED",
                        "{\"attention_request_id\":91,\"decision\":\"REJECTED\"}", false),
                Arguments.of("APPROVAL accepts matching attention identity and decision aliases",
                        "APPROVAL", "{\"attentionRequestId\":91,\"decision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED",
                        "{\"attention_request_id\":91,\"expectedDecision\":\"APPROVED\"}", true),
                Arguments.of("APPROVAL matches approval_id to approvalId",
                        "APPROVAL", "{\"approval_id\":501}",
                        "CHANGE_REQUEST_APPROVED", "{\"approvalId\":501}", true),
                Arguments.of("APPROVAL matches approvalId to governance_action_id numerically",
                        "APPROVAL", "{\"approvalId\":501}",
                        "CHANGE_REQUEST_APPROVED", "{\"governance_action_id\":501.0}", true),
                Arguments.of("APPROVAL matches governance_action_id to governanceActionId",
                        "APPROVAL", "{\"governance_action_id\":502}",
                        "CHANGE_REQUEST_APPROVED", "{\"governanceActionId\":502}", true),
                Arguments.of("APPROVAL matches governanceActionId to approval_id",
                        "APPROVAL", "{\"governanceActionId\":503}",
                        "CHANGE_REQUEST_APPROVED", "{\"approval_id\":503}", true),
                Arguments.of("APPROVAL does not cross-match attention to governance identity",
                        "APPROVAL", "{\"attention_request_id\":91}",
                        "CHANGE_REQUEST_APPROVED", "{\"approval_id\":91}", false),
                Arguments.of("APPROVAL does not cross-match governance to attention identity",
                        "APPROVAL", "{\"governance_action_id\":91}",
                        "CHANGE_REQUEST_APPROVED", "{\"attentionRequestId\":91}", false),
                Arguments.of("APPROVAL requires a declared decision after governance identity matches",
                        "APPROVAL", "{\"approvalId\":501,\"expected_decision\":\"APPROVED\"}",
                        "CHANGE_REQUEST_APPROVED",
                        "{\"governanceActionId\":501,\"decision\":\"REJECTED\"}", false),
                Arguments.of("APPROVAL rejects contradictory aliases for one governance identity",
                        "APPROVAL", "{\"approval_id\":501,\"governance_action_id\":999}",
                        "CHANGE_REQUEST_APPROVED", "{\"approvalId\":501}", false),

                Arguments.of("EMAIL_SENT matches when every declared selector matches numerically",
                        "EMAIL_SENT", "{\"case_id\":101,\"workItemId\":202}",
                        "EMAIL_SENT", "{\"caseId\":101.0,\"work_item_id\":202.0}", true),
                Arguments.of("EMAIL_SENT rejects contradictory aliases for one case selector",
                        "EMAIL_SENT", "{\"case_id\":101,\"caseId\":999}",
                        "EMAIL_SENT", "{\"case_id\":101}", false),
                Arguments.of("EMAIL_SENT rejects a mismatching work item despite matching case",
                        "EMAIL_SENT", "{\"case_id\":101,\"work_item_id\":202}",
                        "EMAIL_SENT", "{\"caseId\":101,\"workItemId\":203}", false),
                Arguments.of("EMAIL_SENT rejects a mismatching case despite matching work item",
                        "EMAIL_SENT", "{\"caseId\":101,\"workItemId\":202}",
                        "EMAIL_SENT", "{\"case_id\":102,\"work_item_id\":202}", false),
                Arguments.of("EMAIL_SENT requires at least one selector",
                        "EMAIL_SENT", "{}", "EMAIL_SENT",
                        "{\"case_id\":101,\"work_item_id\":202}", false),
                Arguments.of("EMAIL_SENT rejects a missing event selector when another selector matches",
                        "EMAIL_SENT", "{\"case_id\":101,\"work_item_id\":202}",
                        "EMAIL_SENT", "{\"caseId\":101}", false),
                Arguments.of("EMAIL_SENT fails closed for a null declared selector",
                        "EMAIL_SENT", "{\"case_id\":null,\"work_item_id\":202}",
                        "EMAIL_SENT", "{\"case_id\":101,\"work_item_id\":202}", false),
                Arguments.of("EMAIL_SENT ignores event selectors absent from the condition",
                        "EMAIL_SENT", "{\"work_item_id\":202}",
                        "EMAIL_SENT", "{\"case_id\":999,\"workItemId\":202}", true),

                Arguments.of("EXTERNAL_DATA accepts a non-stock THIRD_PARTY received event",
                        "EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}",
                        "THIRD_PARTY_SHIPMENT_RECEIVED", "{\"source\":\"3PL\"}", true),
                Arguments.of("EXTERNAL_DATA accepts an exact snake-case event type constraint",
                        "EXTERNAL_DATA",
                        "{\"expected_source\":\"3PL\",\"event_type\":\"THIRD_PARTY_STOCK_REPORT_RECEIVED\"}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"source\":\"3PL\"}", true),
                Arguments.of("EXTERNAL_DATA accepts an exact camel-case event type constraint",
                        "EXTERNAL_DATA",
                        "{\"expectedSource\":\"3PL\",\"eventType\":\"THIRD_PARTY_FORECAST_RECEIVED\"}",
                        "THIRD_PARTY_FORECAST_RECEIVED", "{\"expectedSource\":\"3PL\"}", true),
                Arguments.of("EXTERNAL_DATA rejects a mismatching exact event type constraint",
                        "EXTERNAL_DATA",
                        "{\"expected_source\":\"3PL\",\"event_type\":\"THIRD_PARTY_FORECAST_RECEIVED\"}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"source\":\"3PL\"}", false),
                Arguments.of("EXTERNAL_DATA rejects events outside the THIRD_PARTY received class",
                        "EXTERNAL_DATA", "{\"expected_source\":\"3PL\"}",
                        "THIRD_PARTY_STOCK_REPORT_SENT", "{\"source\":\"3PL\"}", false),
                Arguments.of("EXTERNAL_DATA still requires expected source",
                        "EXTERNAL_DATA", "{\"event_type\":\"THIRD_PARTY_FORECAST_RECEIVED\"}",
                        "THIRD_PARTY_FORECAST_RECEIVED", "{\"source\":\"3PL\"}", false),
                Arguments.of("EXTERNAL_DATA fails closed for a null event type constraint",
                        "EXTERNAL_DATA", "{\"expected_source\":\"3PL\",\"event_type\":null}",
                        "THIRD_PARTY_STOCK_REPORT_RECEIVED", "{\"source\":\"3PL\"}", false));
    }

    private static WaitingCondition condition(String type, String json) throws JacksonException {
        return new WaitingCondition(type, OBJECT_MAPPER.readValue(json, MAP_TYPE));
    }

    private static DispatchEvent event(String eventType, String json) throws JacksonException {
        return new DispatchEvent(eventType, null, null,
                OBJECT_MAPPER.readValue(json, MAP_TYPE), NOW);
    }
}
