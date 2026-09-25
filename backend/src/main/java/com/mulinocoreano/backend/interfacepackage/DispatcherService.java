package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.interfacepackage.DispatcherRepository.*;

import com.mulinocoreano.backend.followup.ReplenishmentFollowupService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DispatcherService {

    private static final String MANUAL_DISPATCH_EVENT = "DISPATCH_REQUESTED";
    private static final String MONITOR_DISPATCH_EVENT = "DISPATCH_SWEEP_TRIGGERED";

    private final ReplenishmentFollowupService followups;
    private final DispatcherRepository repository;
    private final EventPreparation eventPreparation;
    private final ObjectMapper objectMapper;
    private final WaitingConditionMatcher matcher;
    private final RunService runService;

    public DispatcherService(
            DispatcherRepository repository,
            ObjectMapper objectMapper,
            WaitingConditionMatcher matcher,
            RunService runService,
            ReplenishmentFollowupService followups) {
        this.followups = followups;
        this.repository = repository;
        this.eventPreparation = new EventPreparation(repository);
        this.objectMapper = objectMapper;
        this.matcher = matcher;
        this.runService = runService;
    }

    @Transactional
    public EventDispatchResponse ingest(CreateEventRequest request) {
        EventPreparation.PreparedEvent prepared = eventPreparation.prepare(request);
        EventScope scope = prepared.scope();
        InsertedEvent inserted =
                insertEvent(
                        prepared.eventType(), prepared.externalRef(), scope,
                        prepared.actor(), prepared.payload());
        if (!inserted.created()) {
            return emptyResponse(inserted.eventId());
        }
        linkClaimEvidence(prepared.claimEvidence());

        DispatchEvent event =
                new DispatchEvent(
                        prepared.eventType(),
                        scope.caseId(),
                        scope.workItemId(),
                        inserted.payload(),
                        inserted.occurredAt());
        return dispatch(inserted.eventId(), event, scope);
    }

    @Transactional
    public EventDispatchResponse dispatchScheduled() {
        followups.sweepDue();
        Instant requestedAt = Instant.now();
        Map<String, Object> payload = scheduledPayload(requestedAt, "MANUAL");
        return recordScheduledDispatch(MANUAL_DISPATCH_EVENT, "dispatch", payload);
    }

    @Transactional
    public Optional<EventDispatchResponse> dispatchScheduledIfActionable() {
        List<Long> followupEvents = followups.sweepDue();
        Instant requestedAt = Instant.now();
        Map<String, Object> payload = scheduledPayload(requestedAt, "MONITOR");
        EventScope allCases = new EventScope(null, null, null, null);
        DispatchEvent preview =
                new DispatchEvent(MONITOR_DISPATCH_EVENT, null, null, payload, requestedAt);
        boolean actionable =
                loadCandidates(allCases, preview, false).stream()
                        .anyMatch(
                                candidate -> {
                                    WaitingCondition condition =
                                            new WaitingCondition(
                                                    candidate.conditionType(),
                                                    candidate.conditionPayload());
                                    return matcher.matches(
                                            condition,
                                            enrichManualDependencyState(condition, preview),
                                            requestedAt);
                                });
        if (!actionable) {
            return followupEvents.isEmpty()
                    ? Optional.empty()
                    : Optional.of(emptyResponse(followupEvents.getLast()));
        }
        return Optional.of(
                recordScheduledDispatch(MONITOR_DISPATCH_EVENT, "dispatch-sweep", payload));
    }

    private Map<String, Object> scheduledPayload(Instant requestedAt, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dispatchedAt", requestedAt.toString());
        payload.put("source", source);
        payload.put("dependencyStates", currentTerminalDependencyStates(requestedAt));
        return Collections.unmodifiableMap(payload);
    }

    private EventDispatchResponse recordScheduledDispatch(
            String eventType, String externalRefPrefix, Map<String, Object> payload) {
        String externalRef = externalRefPrefix + "-" + UUID.randomUUID();
        EventScope allCases = new EventScope(null, null, null, null);
        InsertedEvent inserted =
                insertEvent(eventType, externalRef, allCases, EventActor.none(), payload);
        DispatchEvent event =
                new DispatchEvent(eventType, null, null, inserted.payload(), inserted.occurredAt());
        return dispatch(inserted.eventId(), event, allCases);
    }

    public List<EventDto> listEvents(String caseRef) {
        String normalizedCaseRef = normalizeScopeRef(caseRef);
        if (caseRef != null && normalizedCaseRef == null) {
            throw new InvalidInterfaceRequestException("caseRef must not be blank");
        }
        return repository.listEvents(normalizedCaseRef);
    }

    private EventDispatchResponse dispatch(long eventId, DispatchEvent event, EventScope scope) {
        List<WaitingCandidate> candidates = loadCandidates(scope, event, true);
        List<String> satisfiedWaiting = new ArrayList<>();
        Map<Long, RunnableWorkItem> runnable = new LinkedHashMap<>();

        Instant now = Instant.now();
        for (WaitingCandidate candidate : candidates) {
            if (!repository.lockCaseIsActive(candidate.caseId())) continue;
            WaitingCondition condition =
                    new WaitingCondition(candidate.conditionType(), candidate.conditionPayload());
            DispatchEvent candidateEvent = enrichManualDependencyState(condition, event);
            if (!matcher.matches(condition, candidateEvent, now)) {
                continue;
            }

            int waitingUpdated = repository.satisfyWaiting(candidate.waitingId(), eventId);
            if (waitingUpdated == 0) {
                continue;
            }

            satisfiedWaiting.add(candidate.waitingRef());
            long activeConditions = repository.activeConditions(candidate.workItemId());
            if (activeConditions > 0) {
                continue;
            }
            int workItemUpdated = repository.makeReady(candidate.workItemId());
            if (workItemUpdated == 1) {
                runnable.putIfAbsent(
                        candidate.workItemId(),
                        new RunnableWorkItem(
                                candidate.workItemId(),
                                candidate.workItemRef(),
                                candidate.caseId(),
                                candidate.caseRef(),
                                candidate.agentKey(),
                                candidate.assignedUserId()));
            }
        }

        List<String> scheduledRuns = new ArrayList<>();
        List<String> failedRuns = new ArrayList<>();
        Set<String> readyWorkItems = new LinkedHashSet<>();
        for (RunnableWorkItem workItem : runnable.values()) {
            readyWorkItems.add(workItem.workItemRef());
            if (workItem.agentKey() == null || !lockAndCheckActiveAgent(workItem.agentKey())) {
                if (workItem.assignedUserId() == null) {
                    openUnassignedAttention(workItem);
                }
                continue;
            }
            runService
                    .tryCreateRun(
                            new CreateRunRequest(
                                    workItem.agentKey(),
                                    workItem.caseRef(),
                                    workItem.workItemRef(),
                                    runService.defaultRuntime()),
                            eventId)
                    .ifPresent(
                            run -> {
                                if ("QUEUED".equals(run.status())) {
                                    scheduledRuns.add(run.runRef());
                                } else {
                                    failedRuns.add(run.runRef());
                                    openRunFailureAttention(workItem, run);
                                }
                            });
        }

        return new EventDispatchResponse(
                eventId,
                satisfiedWaiting,
                new ArrayList<>(readyWorkItems),
                scheduledRuns,
                failedRuns);
    }

    private void openUnassignedAttention(RunnableWorkItem workItem) {
        repository.openAttention(
                workItem.caseId(),
                workItem.workItemId(),
                "MISSING_HUMAN_CONTEXT",
                "Work Item 담당자 필요",
                "대기가 해소된 Work Item을 누가 이어서 처리해야 합니까?",
                "담당자가 지정될 때까지 후속 실행이 시작되지 않습니다.");
    }

    private boolean lockAndCheckActiveAgent(String agentKey) {
        return repository.lockAndCheckActiveAgent(agentKey);
    }

    private void openRunFailureAttention(RunnableWorkItem workItem, RunDto run) {
        repository.openAttention(
                workItem.caseId(),
                workItem.workItemId(),
                "MATERIAL_EXCEPTION",
                "Run 컨텍스트 재구성 실패",
                "Run " + run.runRef() + "의 컨텍스트 재구성 실패를 어떻게 해결해야 합니까?",
                "컨텍스트를 복구하거나 원인을 해소한 뒤 Work Item 실행을 다시 요청해야 합니다.");
    }

    private DispatchEvent enrichManualDependencyState(
            WaitingCondition condition, DispatchEvent event) {
        if (!"DEPENDENCY_DONE".equals(condition.type())
                || (!MANUAL_DISPATCH_EVENT.equals(event.eventType())
                        && !MONITOR_DISPATCH_EVENT.equals(event.eventType()))) {
            return event;
        }
        Object reference =
                firstPayloadValue(condition.payload(), "dependent_wi_ref", "dependentWiRef");
        if (reference == null) {
            return event;
        }
        Optional<Map<String, Object>> observation =
                dependencyObservation(event.payload(), reference.toString());
        if (observation.isEmpty()) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("workItemRef", reference.toString());
        payload.put("status", observation.get().get("status"));
        return new DispatchEvent(
                event.eventType(), event.caseId(), event.workItemId(), payload, event.occurredAt());
    }

    private List<Map<String, Object>> currentTerminalDependencyStates(Instant observedAt) {
        return repository.currentTerminalDependencyStates(observedAt);
    }

    private Optional<Map<String, Object>> dependencyObservation(
            Map<String, Object> payload, String workItemRef) {
        Object value = payload.get("dependencyStates");
        if (!(value instanceof List<?> states)) {
            return Optional.empty();
        }
        for (Object state : states) {
            if (state instanceof Map<?, ?> row
                    && workItemRef.equals(row.get("workItemRef"))
                    && row.get("status") != null) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                row.forEach((key, item) -> normalized.put(String.valueOf(key), item));
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private List<WaitingCandidate> loadCandidates(
            EventScope scope, DispatchEvent event, boolean lockRows) {
        boolean dependencyEvent =
                event != null && "WORK_ITEM_STATUS_CHANGED".equals(event.eventType());
        return repository.loadCandidates(
                scope,
                dependencyEvent,
                dependencyEvent ? Objects.toString(event.payload().get("workItemRef"), null) : null,
                lockRows);
    }

    private InsertedEvent insertEvent(
            String eventType,
            String externalRef,
            EventScope scope,
            EventActor actor,
            Map<String, Object> payload) {
        Optional<InsertedEvent> inserted =
                repository.insertEvent(eventType, externalRef, scope, actor, writePayload(payload));
        if (inserted.isPresent()) {
            return inserted.get();
        }
        if (externalRef == null) {
            throw new IllegalStateException(
                    "Event insert produced no row without an idempotency key");
        }
        ExistingEvent existing =
                repository.existingEvent(
                        eventType, externalRef, scope, actor, writePayload(payload));
        if (!existing.sameContent()) {
            throw new EventIdempotencyConflictException(eventType, externalRef);
        }
        return new InsertedEvent(
                existing.eventId(), existing.occurredAt(), false, existing.payload());
    }

    private void linkClaimEvidence(PreparedClaimEvidence prepared) {
        if (prepared.claimId() == null) {
            return;
        }
        repository.linkClaimEvidence(
                prepared.claimId(), prepared.evidenceId(), prepared.relation());
    }

    private Object firstPayloadValue(Map<String, Object> payload, String... keys) {
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

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new InvalidInterfaceRequestException("Event payload is not valid JSON", e);
        }
    }

    private String normalizeScopeRef(String reference) {
        return reference == null || reference.isBlank() ? null : reference.trim();
    }

    private EventDispatchResponse emptyResponse(long eventId) {
        return new EventDispatchResponse(eventId, List.of(), List.of(), List.of(), List.of());
    }

    private record RunnableWorkItem(
            long workItemId,
            String workItemRef,
            long caseId,
            String caseRef,
            String agentKey,
            Long assignedUserId) {}
}
