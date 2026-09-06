package com.mulinocoreano.backend.interfacepackage;

import static com.mulinocoreano.backend.generated.Tables.AGENTS;
import static com.mulinocoreano.backend.generated.Tables.ATTENTION_REQUESTS;
import static com.mulinocoreano.backend.generated.Tables.CASES;
import static com.mulinocoreano.backend.generated.Tables.PRODUCTS;
import static com.mulinocoreano.backend.generated.Tables.STOCK;
import static com.mulinocoreano.backend.generated.Tables.WAITING_CONDITIONS;
import static com.mulinocoreano.backend.generated.Tables.WAREHOUSES;
import static com.mulinocoreano.backend.generated.Tables.WORK_ITEMS;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.currentLocalDateTime;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.position;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

import com.mulinocoreano.backend.generated.enums.AttentionReasonType;
import com.mulinocoreano.backend.generated.enums.AttentionRequestStatus;
import com.mulinocoreano.backend.generated.enums.CaseStatus;
import com.mulinocoreano.backend.generated.enums.WaitingStatus;
import com.mulinocoreano.backend.generated.enums.WorkItemStatus;
import com.mulinocoreano.backend.planning.CanonicalJson;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Existing read API projections. No dispatch, state transition or business write occurs here. */
@Repository
public class InterfaceReadRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;

    public InterfaceReadRepository(DSLContext dsl, CanonicalJson json) {
        this.dsl = dsl;
        this.json = json;
    }

    public InventoryResult inventory(String query, int limit) {
        var p = PRODUCTS;
        var s = STOCK;
        var w = WAREHOUSES;
        var filter =
                query == null
                        ? noCondition()
                        : position(lower(p.NAME), lower(val(query)))
                                .gt(0)
                                .or(position(lower(p.SKU), lower(val(query))).gt(0));
        var total = count().over().cast(Long.class);
        var rows =
                dsl.select(p.NAME, p.SKU, s.QUANTITY, w.NAME, total)
                        .from(s)
                        .join(p)
                        .on(p.PRODUCT_ID.eq(s.PRODUCT_ID))
                        .join(w)
                        .on(w.WAREHOUSE_ID.eq(s.WAREHOUSE_ID))
                        .where(filter)
                        .orderBy(p.NAME, p.SKU, w.NAME, w.WAREHOUSE_ID)
                        .limit(limit)
                        .fetch();
        var locations =
                rows.map(r -> new InventoryDto(r.value1(), r.value2(), r.value3(), r.value4()));
        return new InventoryResult(locations, rows.isEmpty() ? 0 : rows.getFirst().value5());
    }

    public Optional<CaseDto> findCase(String caseRef) {
        return dsl.select(CASES.fields())
                .from(CASES)
                .where(CASES.CASE_REF.eq(caseRef))
                .fetchOptional(this::caseDto);
    }

    public List<CaseDto> cases(CaseStatus status) {
        return dsl.select(CASES.fields())
                .from(CASES)
                .where(status == null ? noCondition() : CASES.STATUS.eq(status))
                .orderBy(CASES.OPENED_AT.desc())
                .fetch(this::caseDto);
    }

    public List<WorkItemDto> workItems(String caseRef) {
        var w = WORK_ITEMS;
        var c = CASES;
        var a = AGENTS;
        var wait = WAITING_CONDITIONS;
        var reason =
                field(
                        select(wait.REASON)
                                .from(wait)
                                .where(
                                        wait.WORK_ITEM_ID
                                                .eq(w.WORK_ITEM_ID)
                                                .and(wait.STATUS.eq(WaitingStatus.ACTIVE)))
                                .orderBy(wait.CREATED_AT.desc())
                                .limit(1));
        return dsl.select(
                        w.WORK_ITEM_ID,
                        w.WORK_ITEM_REF,
                        w.TITLE,
                        w.STATUS,
                        coalesce(a.DISPLAY_NAME, "(미배정)"),
                        reason,
                        w.DUE_AT)
                .from(w)
                .join(c)
                .on(c.CASE_ID.eq(w.CASE_ID))
                .leftJoin(a)
                .on(a.AGENT_ID.eq(w.ASSIGNED_AGENT_ID))
                .where(c.CASE_REF.eq(caseRef))
                .orderBy(w.WORK_ITEM_ID)
                .fetch(
                        r ->
                                new WorkItemDto(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4().getLiteral(),
                                        r.value5(),
                                        r.value6(),
                                        instant(r.value7())));
    }

    public List<AttentionDto> attention(Integer limit) {
        var a = ATTENTION_REQUESTS;
        var query =
                dsl.select(
                                a.ATTENTION_REQUEST_ID,
                                CASES.CASE_REF,
                                a.REASON_TYPE,
                                a.TITLE,
                                a.QUESTION,
                                a.CONSEQUENCE,
                                a.STATUS,
                                a.CREATED_AT)
                        .from(a)
                        .join(CASES)
                        .on(CASES.CASE_ID.eq(a.CASE_ID))
                        .where(a.STATUS.eq(AttentionRequestStatus.OPEN))
                        .orderBy(a.CREATED_AT.desc());
        var rows = limit == null ? query.fetch() : query.limit(limit).fetch();
        return rows.map(
                r ->
                        new AttentionDto(
                                r.value1(),
                                r.value2(),
                                r.value3().getLiteral(),
                                r.value4(),
                                r.value5(),
                                r.value6(),
                                r.value7().getLiteral(),
                                instant(r.value8())));
    }

    public Counts counts() {
        var c = CASES;
        var w = WORK_ITEMS;
        var a = ATTENTION_REQUESTS;
        var casesOpen =
                field(
                        select(count().cast(Long.class))
                                .from(c)
                                .where(c.STATUS.in(CaseStatus.OPEN, CaseStatus.IN_PROGRESS)));
        var overdue =
                exists(
                        selectOne()
                                .from(w)
                                .where(w.CASE_ID.eq(c.CASE_ID))
                                .and(w.STATUS.notIn(WorkItemStatus.DONE, WorkItemStatus.CANCELLED))
                                .and(w.DUE_AT.lt(currentLocalDateTime())));
        var exception =
                exists(
                        selectOne()
                                .from(a)
                                .where(a.CASE_ID.eq(c.CASE_ID))
                                .and(a.STATUS.eq(AttentionRequestStatus.OPEN))
                                .and(a.REASON_TYPE.eq(AttentionReasonType.MATERIAL_EXCEPTION)));
        var casesAtRisk =
                field(
                        select(count().cast(Long.class))
                                .from(c)
                                .where(
                                        c.STATUS.in(
                                                CaseStatus.OPEN,
                                                CaseStatus.IN_PROGRESS,
                                                CaseStatus.WAITING))
                                .and(overdue.or(exception)));
        var ready =
                field(
                        select(count().cast(Long.class))
                                .from(w)
                                .where(w.STATUS.eq(WorkItemStatus.READY)));
        var waiting =
                field(
                        select(count().cast(Long.class))
                                .from(w)
                                .where(w.STATUS.eq(WorkItemStatus.WAITING)));
        var attention =
                field(
                        select(count().cast(Long.class))
                                .from(a)
                                .where(a.STATUS.eq(AttentionRequestStatus.OPEN)));
        return dsl.select(casesOpen, casesAtRisk, ready, waiting, attention)
                .fetchSingle(
                        r ->
                                new Counts(
                                        r.value1(),
                                        r.value2(),
                                        r.value3(),
                                        r.value4(),
                                        r.value5()));
    }

    private CaseDto caseDto(Record row) {
        var metadata = row.get(CASES.METADATA);
        return new CaseDto(
                row.get(CASES.CASE_ID),
                row.get(CASES.CASE_REF),
                row.get(CASES.TITLE),
                row.get(CASES.OBJECTIVE),
                row.get(CASES.STATUS).getLiteral(),
                row.get(CASES.INTENT_TYPE).getLiteral(),
                instant(row.get(CASES.OPENED_AT)),
                json.readTree(metadata == null ? "{}" : metadata.data()),
                false);
    }

    private Instant instant(LocalDateTime value) {
        // Preserve the existing JDBC semantics of legacy TIMESTAMP WITHOUT TIME ZONE columns.
        return value == null ? null : Timestamp.valueOf(value).toInstant();
    }

    public record InventoryResult(List<InventoryDto> locations, long totalLocations) {}

    public record Counts(
            long casesOpen, long casesAtRisk, long ready, long waiting, long attention) {}
}
