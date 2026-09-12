package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.CASE_PARTICIPANTS;
import static com.mulinocoreano.backend.generated.Tables.CHANNELS;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_CASES;
import static com.mulinocoreano.backend.generated.Tables.PLANNING_POLICIES;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTS;
import static com.mulinocoreano.backend.generated.Tables.USERS;
import static com.mulinocoreano.backend.generated.Tables.WAREHOUSES;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.upper;

import com.mulinocoreano.backend.generated.enums.ActorType;
import com.mulinocoreano.backend.generated.enums.ChannelType;
import com.mulinocoreano.backend.generated.enums.IntentType;
import com.mulinocoreano.backend.generated.enums.ProductType;
import com.mulinocoreano.backend.generated.enums.UserRole;
import com.mulinocoreano.backend.generated.enums.WorkItemStatus;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Storage and lock operations for one authenticated goal-intake transaction. */
@Repository
public class CaseIntakeRepository {
    private static final String DEFAULT_CHANNEL_REF = "SYSTEM_DEFAULT";
    private final DSLContext dsl;
    private final CanonicalJson json;

    public CaseIntakeRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public boolean lockDelegatingUser(long userId) {
        return dsl.select(USERS.USER_ID)
                .from(USERS)
                .where(USERS.USER_ID.eq(userId))
                .and(USERS.IS_ACTIVE.isTrue())
                .and(USERS.ROLE.in(UserRole.OPERATOR, UserRole.MANAGER))
                .forShare()
                .fetchOptional()
                .isPresent();
    }

    public void lockWarehouse(long warehouseId) {
        dsl.select(WAREHOUSES.WAREHOUSE_ID)
                .from(WAREHOUSES)
                .where(WAREHOUSES.WAREHOUSE_ID.eq(warehouseId))
                .forUpdate()
                .fetchSingle();
    }

    public Optional<String> activePlanningCase(long warehouseId) {
        return dsl.select(CASES.CASE_REF)
                .from(PLANNING_CASES)
                .join(CASES)
                .on(CASES.CASE_ID.eq(PLANNING_CASES.CASE_ID))
                .where(
                        PLANNING_CASES
                                .WAREHOUSE_ID
                                .eq(warehouseId)
                                .and(PLANNING_CASES.STATUS.eq("ACTIVE")))
                .fetchOptional(CASES.CASE_REF);
    }

    public Optional<Long> activeOrchestrator() {
        return dsl.select(AGENTS.AGENT_ID)
                .from(AGENTS)
                .where(AGENTS.AGENT_KEY.eq("ORCHESTRATOR"))
                .and(AGENTS.IS_ACTIVE.isTrue())
                .forShare()
                .fetchOptional(AGENTS.AGENT_ID);
    }

    public Optional<Long> defaultChannel(String channel) {
        return dsl.select(CHANNELS.CHANNEL_ID)
                .from(CHANNELS)
                .where(CHANNELS.CHANNEL_TYPE.eq(ChannelType.valueOf(channel)))
                .and(CHANNELS.EXTERNAL_REF.eq(DEFAULT_CHANNEL_REF))
                .fetchOptional(CHANNELS.CHANNEL_ID);
    }

    public long insertCase(
            String ref,
            String title,
            String objective,
            long channelId,
            long userId,
            Map<String, Object> metadata) {
        return dsl.insertInto(CASES)
                .set(CASES.CASE_REF, ref)
                .set(CASES.TITLE, title)
                .set(CASES.OBJECTIVE, objective)
                .set(CASES.INTENT_TYPE, IntentType.ACT)
                .set(CASES.ORIGIN_CHANNEL_ID, channelId)
                .set(CASES.OPENED_BY_USER_ID, userId)
                .set(CASES.METADATA, JSONB.valueOf(json.write(metadata)))
                .returningResult(CASES.CASE_ID)
                .fetchSingle(CASES.CASE_ID);
    }

    public void addHumanParticipant(long caseId, long userId) {
        dsl.insertInto(CASE_PARTICIPANTS)
                .set(CASE_PARTICIPANTS.CASE_ID, caseId)
                .set(CASE_PARTICIPANTS.ACTOR_TYPE, ActorType.USER)
                .set(CASE_PARTICIPANTS.USER_ID, userId)
                .set(CASE_PARTICIPANTS.ROLE, "요청자")
                .onConflictDoNothing()
                .execute();
    }

    public void addAgentParticipant(long caseId, long agentId) {
        dsl.insertInto(CASE_PARTICIPANTS)
                .set(CASE_PARTICIPANTS.CASE_ID, caseId)
                .set(CASE_PARTICIPANTS.ACTOR_TYPE, ActorType.AGENT)
                .set(CASE_PARTICIPANTS.AGENT_ID, agentId)
                .execute();
    }

    public void bindPlanningCase(long caseId, long warehouseId) {
        dsl.insertInto(PLANNING_CASES)
                .set(PLANNING_CASES.CASE_ID, caseId)
                .set(PLANNING_CASES.WAREHOUSE_ID, warehouseId)
                .execute();
    }

    public void insertInitialWork(String ref, long caseId, long agentId) {
        dsl.insertInto(WORK_ITEMS)
                .set(WORK_ITEMS.WORK_ITEM_REF, ref)
                .set(WORK_ITEMS.CASE_ID, caseId)
                .set(WORK_ITEMS.TITLE, "목표 분해 및 계획 수립")
                .set(WORK_ITEMS.STATUS, WorkItemStatus.READY)
                .set(WORK_ITEMS.ASSIGNED_AGENT_ID, agentId)
                .execute();
    }

    public List<PlanningPolicy> policies(Long warehouseId) {
        var query =
                dsl.select(PLANNING_POLICIES.WAREHOUSE_ID, PLANNING_POLICIES.HORIZON_DAYS)
                        .from(PLANNING_POLICIES)
                        .where(
                                warehouseId == null
                                        ? noCondition()
                                        : PLANNING_POLICIES.WAREHOUSE_ID.eq(warehouseId))
                        .orderBy(PLANNING_POLICIES.WAREHOUSE_ID);
        var rows = warehouseId == null ? query.limit(2).fetch() : query.fetch();
        return rows.map(r -> new PlanningPolicy(r.value1(), r.value2()));
    }

    public List<ProductTarget> products(List<String> skus) {
        return dsl.select(PRODUCTS.PRODUCT_ID, PRODUCTS.SKU)
                .from(PRODUCTS)
                .where(upper(PRODUCTS.SKU).in(skus))
                .and(PRODUCTS.IS_ACTIVE.isTrue())
                .and(PRODUCTS.PRODUCT_TYPE.eq(ProductType.FINISHED_GOODS))
                .orderBy(PRODUCTS.PRODUCT_ID)
                .fetch(r -> new ProductTarget(r.value1(), r.value2()));
    }

    public record PlanningPolicy(long warehouseId, int horizon) {}

    public record ProductTarget(long id, String sku) {}
}
