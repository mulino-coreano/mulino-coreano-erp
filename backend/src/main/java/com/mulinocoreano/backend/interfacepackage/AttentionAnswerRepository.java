package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.*;
import static org.jooq.impl.DSL.selectOne;

import com.mulinocoreano.backend.generated.enums.*;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Repository
public class AttentionAnswerRepository {
    private final DSLContext dsl;

    public AttentionAnswerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isManagedWork(long workId) {
        return dsl.fetchExists(
                selectOne().from(REPLENISHMENT_FOLLOWUPS)
                        .where(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(workId)));
    }

    public boolean lockHuman(long id) {
        return dsl.select(USERS.USER_ID)
                .from(USERS)
                .where(USERS.USER_ID.eq(id))
                .and(USERS.IS_ACTIVE.isTrue())
                .and(USERS.ROLE.in(UserRole.OPERATOR, UserRole.MANAGER))
                .forShare()
                .fetchOptional()
                .isPresent();
    }

    public Record attention(long id, boolean lock) {
        var q =
                dsl.selectFrom(ATTENTION_REQUESTS)
                        .where(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID.eq(id));
        return (lock ? q.forUpdate().fetchOptional() : q.fetchOptional())
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Attention not found"));
    }

    public Record lockWork(Long id) {
        return id == null
                ? null
                : dsl.selectFrom(WORK_ITEMS)
                        .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                        .forUpdate()
                        .fetchSingle();
    }

    public Record lockCase(long id) {
        return dsl.selectFrom(CASES).where(CASES.CASE_ID.eq(id)).forUpdate().fetchSingle();
    }

    public long decision(Record a, AttentionAnswerRequest r, long user, JSONB metadata) {
        return dsl.insertInto(DECISIONS)
                .set(DECISIONS.CASE_ID, a.get(ATTENTION_REQUESTS.CASE_ID))
                .set(DECISIONS.WORK_ITEM_ID, a.get(ATTENTION_REQUESTS.WORK_ITEM_ID))
                .set(DECISIONS.DECISION_TEXT, r.answer())
                .set(DECISIONS.SCOPE, DecisionScope.valueOf(r.scope().name()))
                .set(DECISIONS.DECIDED_BY_USER_ID, user)
                .set(DECISIONS.METADATA, metadata)
                .returning(DECISIONS.DECISION_ID)
                .fetchSingle()
                .get(DECISIONS.DECISION_ID);
    }

    public Record resolve(long id, AttentionAnswerRequest r, long user, LocalDateTime now) {
        return dsl.update(ATTENTION_REQUESTS)
                .set(ATTENTION_REQUESTS.STATUS, AttentionRequestStatus.ANSWERED)
                .set(ATTENTION_REQUESTS.ANSWER_TEXT, r.answer())
                .set(ATTENTION_REQUESTS.ANSWER_SCOPE, DecisionScope.valueOf(r.scope().name()))
                .set(ATTENTION_REQUESTS.RESOLVED_BY_USER_ID, user)
                .set(ATTENTION_REQUESTS.RESOLVED_AT, now)
                .where(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID.eq(id))
                .returning()
                .fetchSingle();
    }

    public void participant(long caseId, long user) {
        dsl.insertInto(CASE_PARTICIPANTS)
                .set(CASE_PARTICIPANTS.CASE_ID, caseId)
                .set(CASE_PARTICIPANTS.ACTOR_TYPE, ActorType.USER)
                .set(CASE_PARTICIPANTS.USER_ID, user)
                .set(CASE_PARTICIPANTS.ROLE, "Attention 응답자")
                .onConflictDoNothing()
                .execute();
    }

    public long event(Record a, long user, JSONB payload) {
        return dsl.insertInto(EVENTS)
                .set(EVENTS.EVENT_TYPE, "ATTENTION_ANSWER_RECORDED")
                .set(
                        EVENTS.EXTERNAL_REF,
                        "attention-answer-"
                                + a.get(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID)
                                + "-v"
                                + a.get(ATTENTION_REQUESTS.VERSION))
                .set(EVENTS.CASE_ID, a.get(ATTENTION_REQUESTS.CASE_ID))
                .set(EVENTS.WORK_ITEM_ID, a.get(ATTENTION_REQUESTS.WORK_ITEM_ID))
                .set(EVENTS.ACTOR_TYPE, ActorType.USER)
                .set(EVENTS.USER_ID, user)
                .set(EVENTS.PAYLOAD, payload)
                .returning(EVENTS.EVENT_ID)
                .fetchSingle()
                .get(EVENTS.EVENT_ID);
    }

    public boolean hasOpenAttention(long caseId, long workId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(ATTENTION_REQUESTS)
                        .where(ATTENTION_REQUESTS.CASE_ID.eq(caseId))
                        .and(
                                ATTENTION_REQUESTS
                                        .WORK_ITEM_ID
                                        .eq(workId)
                                        .or(ATTENTION_REQUESTS.WORK_ITEM_ID.isNull()))
                        .and(ATTENTION_REQUESTS.STATUS.eq(AttentionRequestStatus.OPEN)));
    }

    public boolean hasWait(long workId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(WAITING_CONDITIONS)
                        .where(WAITING_CONDITIONS.WORK_ITEM_ID.eq(workId))
                        .and(WAITING_CONDITIONS.STATUS.eq(WaitingStatus.ACTIVE)));
    }

    public Record lockAgent(long id) {
        return dsl.selectFrom(AGENTS).where(AGENTS.AGENT_ID.eq(id)).forShare().fetchSingle();
    }

    public void ready(long id) {
        dsl.update(WORK_ITEMS)
                .set(WORK_ITEMS.STATUS, WorkItemStatus.READY)
                .where(WORK_ITEMS.WORK_ITEM_ID.eq(id))
                .execute();
    }
}
