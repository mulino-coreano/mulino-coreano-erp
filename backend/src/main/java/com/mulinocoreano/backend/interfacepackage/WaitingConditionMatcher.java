package com.mulinocoreano.backend.interfacepackage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class WaitingConditionMatcher {

    public boolean matches(WaitingCondition condition, DispatchEvent event, Instant now) {
        if (condition == null || condition.type() == null) {
            return false;
        }

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

    private boolean supplierReply(Map<String, Object> conditionPayload, DispatchEvent event) {
        return hasEventType(event, "SUPPLIER_EMAIL_RECEIVED")
                && (sameValue(conditionPayload, event.payload(),
                        new String[] { "supplier_id", "supplierId" },
                        new String[] { "supplier_id", "supplierId" })
                || sameValue(conditionPayload, event.payload(),
                        new String[] { "po_ref", "poRef" },
                        new String[] { "po_ref", "poRef" }));
    }

    private boolean emailSent(Map<String, Object> conditionPayload, DispatchEvent event) {
        if (!hasEventType(event, "EMAIL_SENT")) {
            return false;
        }

        String[] caseIdKeys = { "case_id", "caseId" };
        String[] workItemIdKeys = { "work_item_id", "workItemId" };
        boolean hasCaseId = hasAnyKey(conditionPayload, caseIdKeys);
        boolean hasWorkItemId = hasAnyKey(conditionPayload, workItemIdKeys);
        return (hasCaseId || hasWorkItemId)
                && (!hasCaseId || sameValue(conditionPayload, event.payload(),
                        caseIdKeys, caseIdKeys))
                && (!hasWorkItemId || sameValue(conditionPayload, event.payload(),
                        workItemIdKeys, workItemIdKeys));
    }

    private boolean approval(Map<String, Object> conditionPayload, DispatchEvent event) {
        if (!hasEventType(event, "CHANGE_REQUEST_APPROVED")) {
            return false;
        }

        boolean attentionIdentityMatches = sameValue(conditionPayload, event.payload(),
                new String[] { "attention_request_id", "attentionRequestId" },
                new String[] { "attention_request_id", "attentionRequestId" });
        boolean governanceIdentityMatches = sameValue(conditionPayload, event.payload(),
                new String[] { "approval_id", "approvalId", "governance_action_id", "governanceActionId" },
                new String[] { "approval_id", "approvalId", "governance_action_id", "governanceActionId" });
        if (!attentionIdentityMatches && !governanceIdentityMatches) {
            return false;
        }

        String[] decisionKeys = { "expected_decision", "expectedDecision", "decision" };
        return !hasAnyKey(conditionPayload, decisionKeys)
                || sameValue(conditionPayload, event.payload(), decisionKeys,
                        new String[] { "decision", "expected_decision", "expectedDecision" });
    }

    private boolean scheduledTime(Map<String, Object> conditionPayload, Instant now) {
        if (now == null) {
            return false;
        }

        Object dueAtValue = firstValue(conditionPayload, "due_at", "dueAt");
        Instant dueAt;
        if (dueAtValue instanceof Instant instant) {
            dueAt = instant;
        } else if (dueAtValue instanceof CharSequence text) {
            try {
                dueAt = Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                return false;
            }
        } else {
            return false;
        }
        return !dueAt.isAfter(now);
    }

    private boolean externalData(Map<String, Object> conditionPayload, DispatchEvent event) {
        if (!hasThirdPartyReceivedType(event)
                || !sameValue(conditionPayload, event.payload(),
                        new String[] { "expected_source", "expectedSource" },
                        new String[] { "source", "expected_source", "expectedSource" })) {
            return false;
        }

        String[] eventTypeKeys = { "event_type", "eventType" };
        return !hasAnyKey(conditionPayload, eventTypeKeys)
                || Objects.equals(firstValue(conditionPayload, eventTypeKeys), event.eventType());
    }

    private boolean dependencyDone(Map<String, Object> conditionPayload, DispatchEvent event) {
        if (!hasEventType(event, "WORK_ITEM_STATUS_CHANGED")
                && !hasEventType(event, "DISPATCH_REQUESTED")
                && !hasEventType(event, "DISPATCH_SWEEP_TRIGGERED")) {
            return false;
        }

        Object status = firstValue(event.payload(), "status", "work_item_status", "workItemStatus");
        boolean terminal = Objects.equals(status, "DONE") || Objects.equals(status, "CANCELLED");
        return terminal && sameValue(conditionPayload, event.payload(),
                new String[] { "dependent_wi_ref", "dependentWiRef" },
                new String[] { "dependent_wi_ref", "dependentWiRef", "work_item_ref", "workItemRef" });
    }

    private boolean hasEventType(DispatchEvent event, String expectedType) {
        return event != null && Objects.equals(event.eventType(), expectedType);
    }

    private boolean hasThirdPartyReceivedType(DispatchEvent event) {
        if (event == null || event.eventType() == null) {
            return false;
        }
        String eventType = event.eventType();
        String prefix = "THIRD_PARTY_";
        String suffix = "_RECEIVED";
        return eventType.startsWith(prefix)
                && eventType.endsWith(suffix)
                && eventType.length() > prefix.length() + suffix.length();
    }

    private boolean sameValue(Map<String, Object> conditionPayload, Map<String, Object> eventPayload,
                              String[] conditionKeys, String[] eventKeys) {
        Object conditionValue = firstValue(conditionPayload, conditionKeys);
        Object eventValue = firstValue(eventPayload, eventKeys);
        if (conditionValue == null || eventValue == null) {
            return false;
        }
        if (conditionValue instanceof Number left && eventValue instanceof Number right) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Objects.equals(conditionValue, eventValue);
    }

    private Object firstValue(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean hasAnyKey(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return false;
        }
        for (String key : keys) {
            if (payload.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
