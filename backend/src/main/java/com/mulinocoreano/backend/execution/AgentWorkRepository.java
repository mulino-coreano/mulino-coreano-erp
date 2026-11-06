package com.mulinocoreano.backend.execution;

import static com.mulinocoreano.backend.generated.Tables.*;

import com.mulinocoreano.backend.generated.enums.ActorType;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Child-work persistence; authorization and the transaction stay in AgentWorkService. */
@Repository
public class AgentWorkRepository {
    private final DSLContext dsl;

    public AgentWorkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Long> lockActiveAgent(String key) {
        return dsl.select(AGENTS.AGENT_ID)
                .from(AGENTS)
                .where(AGENTS.AGENT_KEY.eq(key))
                .and(AGENTS.IS_ACTIVE.isTrue())
                .forShare()
                .fetchOptional(AGENTS.AGENT_ID);
    }

    public String runRef(long id) {
        return dsl.select(RUNS.RUN_REF)
                .from(RUNS)
                .where(RUNS.RUN_ID.eq(id))
                .fetchSingle(RUNS.RUN_REF);
    }

    public void insertWork(
            String ref,
            long caseId,
            String title,
            String description,
            long agent,
            String metadata) {
        dsl.insertInto(WORK_ITEMS)
                .set(WORK_ITEMS.WORK_ITEM_REF, ref)
                .set(WORK_ITEMS.CASE_ID, caseId)
                .set(WORK_ITEMS.TITLE, title)
                .set(WORK_ITEMS.DESCRIPTION, description)
                .set(WORK_ITEMS.ASSIGNED_AGENT_ID, agent)
                .set(WORK_ITEMS.METADATA, JSONB.valueOf(metadata))
                .execute();
    }

    public void addParticipant(long caseId, long agentId) {
        dsl.insertInto(CASE_PARTICIPANTS)
                .set(CASE_PARTICIPANTS.CASE_ID, caseId)
                .set(CASE_PARTICIPANTS.ACTOR_TYPE, ActorType.AGENT)
                .set(CASE_PARTICIPANTS.AGENT_ID, agentId)
                .set(CASE_PARTICIPANTS.ROLE, "업무 담당")
                .onConflictDoNothing()
                .execute();
    }
}
