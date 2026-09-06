package com.mulinocoreano.backend.followup;

import static com.mulinocoreano.backend.generated.Tables.*;
import static org.jooq.impl.DSL.*;

import com.mulinocoreano.backend.generated.enums.*;
import org.jooq.Record;
import com.mulinocoreano.backend.planning.CanonicalJson;
import org.jooq.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Repository
public class ReplenishmentFollowupRepository {
    private final DSLContext dsl;
    private final CanonicalJson json;
    public ReplenishmentFollowupRepository(DSLContext dsl, CanonicalJson json) { this.dsl=dsl; this.json=json; }
    public boolean isManagedWork(long workItemId) { return dsl.fetchExists(REPLENISHMENT_FOLLOWUPS,REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(workItemId)); }
    public boolean hasResponsibility(long parentWorkItemId,long caseId) {
        return dsl.fetchExists(selectOne().from(REPLENISHMENT_FOLLOWUPS).join(WORK_ITEMS).on(WORK_ITEMS.WORK_ITEM_ID.eq(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID))
            .where(REPLENISHMENT_FOLLOWUPS.PARENT_WORK_ITEM_ID.eq(parentWorkItemId).and(REPLENISHMENT_FOLLOWUPS.CASE_ID.eq(caseId)))
            .and(WORK_ITEMS.STATUS.notIn(WorkItemStatus.DONE,WorkItemStatus.CANCELLED)));
    }
    public List<ReplenishmentFollowupDto> forCase(long caseId) {
        var f=REPLENISHMENT_FOLLOWUPS; var s=WORK_ITEMS.as("source"); var p=WORK_ITEMS.as("parent");
        return dsl.select(f.fields()).select(CASES.CASE_REF,REPLENISHMENT_PLANS.PLAN_REF,s.WORK_ITEM_REF,WORK_ITEMS.WORK_ITEM_REF,p.WORK_ITEM_REF,AGENTS.AGENT_KEY,ATTENTION_REQUESTS.STATUS)
            .from(f).join(CASES).on(CASES.CASE_ID.eq(f.CASE_ID)).join(REPLENISHMENT_PLANS).on(REPLENISHMENT_PLANS.REPLENISHMENT_PLAN_ID.eq(f.REPLENISHMENT_PLAN_ID))
            .join(s).on(s.WORK_ITEM_ID.eq(f.SOURCE_WORK_ITEM_ID)).join(WORK_ITEMS).on(WORK_ITEMS.WORK_ITEM_ID.eq(f.WORK_ITEM_ID))
            .join(AGENTS).on(AGENTS.AGENT_ID.eq(WORK_ITEMS.ASSIGNED_AGENT_ID)).leftJoin(p).on(p.WORK_ITEM_ID.eq(f.PARENT_WORK_ITEM_ID))
            .leftJoin(ATTENTION_REQUESTS).on(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID.eq(f.ATTENTION_REQUEST_ID)).where(f.CASE_ID.eq(caseId)).orderBy(f.REPLENISHMENT_FOLLOWUP_ID)
            .fetch(r -> new ReplenishmentFollowupDto(r.get(f.FOLLOWUP_REF),r.get(CASES.CASE_REF),r.get(REPLENISHMENT_PLANS.PLAN_REF),r.get(s.WORK_ITEM_REF),r.get(WORK_ITEMS.WORK_ITEM_REF),r.get(p.WORK_ITEM_REF),r.get(AGENTS.AGENT_KEY),r.get(f.OBSERVATION_STATUS),r.get(f.DUE_AT),r.get(f.ATTENTION_REQUEST_ID),r.get(ATTENTION_REQUESTS.STATUS)==null?null:r.get(ATTENTION_REQUESTS.STATUS).getLiteral(),r.get(f.OBSERVED_AT),json.readTree(r.get(f.OBSERVATION).data()),true));
    }
    Source lockSource(long caseId,long workId) {
        var w=dsl.selectFrom(WORK_ITEMS).where(WORK_ITEMS.WORK_ITEM_ID.eq(workId).and(WORK_ITEMS.CASE_ID.eq(caseId))).forUpdate().fetchOne();
        if(w==null) throw new IllegalStateException("FOLLOWUP_SOURCE_NOT_FOUND");
        lockCase(caseId);
        var role=dsl.select(AGENTS.AGENT_KEY).from(AGENTS).where(AGENTS.AGENT_ID.eq(w.get(WORK_ITEMS.ASSIGNED_AGENT_ID))).fetchOne(AGENTS.AGENT_KEY);
        if(!"PROCUREMENT".equals(role)||w.get(WORK_ITEMS.PROCUREMENT_PLAN_ID)==null||w.get(WORK_ITEMS.STATUS)==WorkItemStatus.CANCELLED) throw new IllegalStateException("FOLLOWUP_SOURCE_INVALID");
        Long parent=null;
        if(w.get(WORK_ITEMS.METADATA)!=null) {
            String ref=json.readTree(w.get(WORK_ITEMS.METADATA).data()).path("parentWorkItemRef").asText();
            parent=dsl.select(WORK_ITEMS.WORK_ITEM_ID).from(WORK_ITEMS).join(AGENTS).on(AGENTS.AGENT_ID.eq(WORK_ITEMS.ASSIGNED_AGENT_ID))
                .where(WORK_ITEMS.CASE_ID.eq(caseId).and(WORK_ITEMS.WORK_ITEM_REF.eq(ref)).and(AGENTS.AGENT_KEY.eq("ORCHESTRATOR"))).fetchOne(WORK_ITEMS.WORK_ITEM_ID);
            if(!ref.isBlank()&&parent==null) throw new IllegalStateException("FOLLOWUP_PARENT_INVALID");
        }
        Long app=dsl.select(PURCHASE_APPLICATIONS.PURCHASE_APPLICATION_ID).from(PURCHASE_APPLICATIONS).where(PURCHASE_APPLICATIONS.CASE_ID.eq(caseId).and(PURCHASE_APPLICATIONS.WORK_ITEM_ID.eq(workId)).and(PURCHASE_APPLICATIONS.REPLENISHMENT_PLAN_ID.eq(w.get(WORK_ITEMS.PROCUREMENT_PLAN_ID)))).fetchOne(PURCHASE_APPLICATIONS.PURCHASE_APPLICATION_ID);
        return new Source(caseId,workId,w.get(WORK_ITEMS.PROCUREMENT_PLAN_ID),parent,app,w.get(WORK_ITEMS.PROCUREMENT_OUTCOME));
    }
    private void lockCase(long id) {
        var state=dsl.select(CASES.STATUS).from(CASES).where(CASES.CASE_ID.eq(id)).forUpdate().fetchOne(CASES.STATUS);
        if(state==null||state==CaseStatus.CLOSED||state==CaseStatus.RESOLVED) throw new IllegalStateException("FOLLOWUP_CASE_TERMINAL");
    }
    Record existing(long planId) { return dsl.selectFrom(REPLENISHMENT_FOLLOWUPS).where(REPLENISHMENT_FOLLOWUPS.REPLENISHMENT_PLAN_ID.eq(planId)).fetchOne(); }
    JsonNode planResult(long planId) { return json.readTree(dsl.select(REPLENISHMENT_PLANS.RESULT).from(REPLENISHMENT_PLANS).where(REPLENISHMENT_PLANS.REPLENISHMENT_PLAN_ID.eq(planId)).fetchSingle(REPLENISHMENT_PLANS.RESULT).data()); }
    Record create(Source s) {
        long agent=dsl.select(AGENTS.AGENT_ID).from(AGENTS).where(AGENTS.AGENT_KEY.eq("ORCHESTRATOR").and(AGENTS.IS_ACTIVE.isTrue())).fetchSingle(AGENTS.AGENT_ID);
        dsl.insertInto(CASE_PARTICIPANTS).set(CASE_PARTICIPANTS.CASE_ID,s.caseId()).set(CASE_PARTICIPANTS.ACTOR_TYPE,ActorType.AGENT).set(CASE_PARTICIPANTS.AGENT_ID,agent).set(CASE_PARTICIPANTS.ROLE,"재보충 후속 책임").onConflictDoNothing().execute();
        long work=dsl.insertInto(WORK_ITEMS).set(WORK_ITEMS.WORK_ITEM_REF,ref("WI")).set(WORK_ITEMS.CASE_ID,s.caseId()).set(WORK_ITEMS.TITLE,"입고 및 생산/재고 후속 확인").set(WORK_ITEMS.STATUS,WorkItemStatus.WAITING).set(WORK_ITEMS.ASSIGNED_AGENT_ID,agent).returning(WORK_ITEMS.WORK_ITEM_ID).fetchSingle().get(WORK_ITEMS.WORK_ITEM_ID);
        var f=dsl.insertInto(REPLENISHMENT_FOLLOWUPS).set(REPLENISHMENT_FOLLOWUPS.FOLLOWUP_REF,ref("FU")).set(REPLENISHMENT_FOLLOWUPS.CASE_ID,s.caseId()).set(REPLENISHMENT_FOLLOWUPS.REPLENISHMENT_PLAN_ID,s.planId()).set(REPLENISHMENT_FOLLOWUPS.SOURCE_WORK_ITEM_ID,s.workId()).set(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID,work).set(REPLENISHMENT_FOLLOWUPS.PARENT_WORK_ITEM_ID,s.parentId()).set(REPLENISHMENT_FOLLOWUPS.PURCHASE_APPLICATION_ID,s.applicationId()).set(REPLENISHMENT_FOLLOWUPS.SOURCE_OUTCOME,s.outcome()).set(REPLENISHMENT_FOLLOWUPS.OBSERVATION_STATUS,"PENDING").returning().fetchSingle();
        dsl.update(CASES).set(CASES.STATUS,CaseStatus.WAITING).where(CASES.CASE_ID.eq(s.caseId())).execute();
        return f;
    }
    List<Long> candidates(OffsetDateTime now) {
        var f=REPLENISHMENT_FOLLOWUPS;
        return dsl.select(f.WORK_ITEM_ID).from(f).join(WORK_ITEMS).on(WORK_ITEMS.WORK_ITEM_ID.eq(f.WORK_ITEM_ID)).join(CASES).on(CASES.CASE_ID.eq(f.CASE_ID))
            .where(f.DUE_AT.isNull().or(f.DUE_AT.le(now))).and(WORK_ITEMS.STATUS.notIn(WorkItemStatus.DONE,WorkItemStatus.CANCELLED)).and(CASES.STATUS.notIn(CaseStatus.CLOSED,CaseStatus.RESOLVED))
            .orderBy(f.OBSERVED_AT.asc().nullsFirst(),f.REPLENISHMENT_FOLLOWUP_ID).limit(100).fetch(f.WORK_ITEM_ID);
    }
    Record lockCandidate(long workId) {
        var w=dsl.selectFrom(WORK_ITEMS).where(WORK_ITEMS.WORK_ITEM_ID.eq(workId)).forUpdate().skipLocked().fetchOne();
        if(w==null||w.get(WORK_ITEMS.STATUS)==WorkItemStatus.DONE||w.get(WORK_ITEMS.STATUS)==WorkItemStatus.CANCELLED)return null;
        var c=dsl.selectFrom(CASES).where(CASES.CASE_ID.eq(w.get(WORK_ITEMS.CASE_ID))).forUpdate().skipLocked().fetchOne();
        if(c==null||c.get(CASES.STATUS)==CaseStatus.CLOSED||c.get(CASES.STATUS)==CaseStatus.RESOLVED)return null;
        return dsl.selectFrom(REPLENISHMENT_FOLLOWUPS).where(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID.eq(workId)).forUpdate().fetchOne();
    }
    /** A single SQL statement gives a coherent receipt/LOT snapshot without acquiring the planning source guard. */
    List<ReceiptLine> receipts(long appId) {
        var p=PURCHASE_ORDERS; var i=PURCHASE_ORDER_ITEMS; var n=INBOUND; var l=RAW_MATERIAL_LOTS;
        var rows=dsl.select(i.PURCHASE_ORDER_ITEM_ID,i.RAW_MATERIAL_ID,i.QUANTITY,i.EXPECTED_DELIVERY_DATE,p.EXPECTED_DELIVERY_DATE,p.SUPPLIER_ID,p.WAREHOUSE_ID,
            n.INBOUND_ID,n.RAW_MATERIAL_ID,n.SUPPLIER_ID,n.WAREHOUSE_ID,n.QUANTITY,n.STATUS,
            l.RAW_MATERIAL_LOT_ID,l.RAW_MATERIAL_ID,l.QUANTITY,l.REMAINING_QUANTITY)
            .from(p).join(i).on(i.PURCHASE_ORDER_ID.eq(p.PURCHASE_ORDER_ID)).leftJoin(n).on(n.PURCHASE_ORDER_ITEM_ID.eq(i.PURCHASE_ORDER_ITEM_ID))
            .leftJoin(l).on(l.INBOUND_ID.eq(n.INBOUND_ID)).where(p.PURCHASE_APPLICATION_ID.eq(appId)).orderBy(i.PURCHASE_ORDER_ITEM_ID,n.INBOUND_ID,l.RAW_MATERIAL_LOT_ID).fetch();
        var lines=new LinkedHashMap<Long,ReceiptLine>();
        for(var r:rows) {
            var line=lines.computeIfAbsent(r.get(i.PURCHASE_ORDER_ITEM_ID), id -> new ReceiptLine(id,r.get(i.QUANTITY),r.get(i.EXPECTED_DELIVERY_DATE)!=null?r.get(i.EXPECTED_DELIVERY_DATE):r.get(p.EXPECTED_DELIVERY_DATE),new LinkedHashMap<>()));
            if(r.get(n.INBOUND_ID)==null)continue;
            var receipt=line.receipts().computeIfAbsent(r.get(n.INBOUND_ID), id -> new Receipt(id,r.get(n.QUANTITY),r.get(n.STATUS).getLiteral(),Objects.equals(r.get(i.RAW_MATERIAL_ID),r.get(n.RAW_MATERIAL_ID))&&Objects.equals(r.get(p.SUPPLIER_ID),r.get(n.SUPPLIER_ID))&&Objects.equals(r.get(p.WAREHOUSE_ID),r.get(n.WAREHOUSE_ID)),new ArrayList<>()));
            if(r.get(l.RAW_MATERIAL_LOT_ID)!=null)receipt.lots().add(new Lot(r.get(l.RAW_MATERIAL_LOT_ID),r.get(l.QUANTITY),Objects.equals(r.get(l.RAW_MATERIAL_ID),r.get(i.RAW_MATERIAL_ID)),r.get(l.REMAINING_QUANTITY)));
        }
        return List.copyOf(lines.values());
    }
    Long observe(Record f,String status,OffsetDateTime due,Map<String,Object> observation,boolean attention,OffsetDateTime now) {
        String hash=json.sha256(observation); boolean changed=!hash.equals(f.get(REPLENISHMENT_FOLLOWUPS.OBSERVATION_HASH));
        if(attention&&f.get(REPLENISHMENT_FOLLOWUPS.ATTENTION_REQUEST_ID)==null) {
            long agent=dsl.select(WORK_ITEMS.ASSIGNED_AGENT_ID).from(WORK_ITEMS).where(WORK_ITEMS.WORK_ITEM_ID.eq(f.get(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID))).fetchSingle(WORK_ITEMS.ASSIGNED_AGENT_ID);
            long id=dsl.insertInto(ATTENTION_REQUESTS).set(ATTENTION_REQUESTS.CASE_ID,f.get(REPLENISHMENT_FOLLOWUPS.CASE_ID)).set(ATTENTION_REQUESTS.WORK_ITEM_ID,f.get(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID)).set(ATTENTION_REQUESTS.REASON_TYPE,AttentionReasonType.JUDGMENT_REQUIRED)
                .set(ATTENTION_REQUESTS.TITLE,"재보충 후속 확인 필요").set(ATTENTION_REQUESTS.QUESTION,"입고 관찰과 남은 생산/재고 확인 책임을 검토해 주세요.").set(ATTENTION_REQUESTS.CONSEQUENCE,"입고 확인만으로 품절 해소나 Case 완료를 확정할 수 없습니다.").set(ATTENTION_REQUESTS.REQUESTED_BY_AGENT_ID,agent).returning(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID).fetchSingle().get(ATTENTION_REQUESTS.ATTENTION_REQUEST_ID);
            f.set(REPLENISHMENT_FOLLOWUPS.ATTENTION_REQUEST_ID,id);
        }
        dsl.update(REPLENISHMENT_FOLLOWUPS).set(REPLENISHMENT_FOLLOWUPS.OBSERVATION_STATUS,status).set(REPLENISHMENT_FOLLOWUPS.DUE_AT,due).set(REPLENISHMENT_FOLLOWUPS.OBSERVATION,JSONB.valueOf(json.write(observation))).set(REPLENISHMENT_FOLLOWUPS.OBSERVATION_HASH,hash).set(REPLENISHMENT_FOLLOWUPS.OBSERVED_AT,now).set(REPLENISHMENT_FOLLOWUPS.ATTENTION_REQUEST_ID,f.get(REPLENISHMENT_FOLLOWUPS.ATTENTION_REQUEST_ID)).where(REPLENISHMENT_FOLLOWUPS.REPLENISHMENT_FOLLOWUP_ID.eq(f.get(REPLENISHMENT_FOLLOWUPS.REPLENISHMENT_FOLLOWUP_ID))).execute();
        if(changed) {
            var active=dsl.select(WAITING_CONDITIONS.WAITING_CONDITION_ID).from(WAITING_CONDITIONS).where(WAITING_CONDITIONS.WORK_ITEM_ID.eq(f.get(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID)).and(WAITING_CONDITIONS.STATUS.eq(WaitingStatus.ACTIVE)).and(WAITING_CONDITIONS.CONDITION_TYPE.eq(WaitingConditionType.SCHEDULED_TIME))).fetchOne(WAITING_CONDITIONS.WAITING_CONDITION_ID);
            if(due==null&&active!=null) dsl.update(WAITING_CONDITIONS).set(WAITING_CONDITIONS.STATUS,WaitingStatus.SATISFIED).set(WAITING_CONDITIONS.RESOLVED_AT,now.toLocalDateTime()).where(WAITING_CONDITIONS.WAITING_CONDITION_ID.eq(active)).execute();
            if(due!=null) {
                JSONB payload=JSONB.valueOf(json.write(Map.of("serverManagedFollowupRef",f.get(REPLENISHMENT_FOLLOWUPS.FOLLOWUP_REF),"dueAt",due.toString())));
                if(active!=null) dsl.update(WAITING_CONDITIONS).set(WAITING_CONDITIONS.CONDITION_PAYLOAD,payload).where(WAITING_CONDITIONS.WAITING_CONDITION_ID.eq(active)).execute();
                else dsl.insertInto(WAITING_CONDITIONS).set(WAITING_CONDITIONS.WAITING_REF,ref("WAIT")).set(WAITING_CONDITIONS.WORK_ITEM_ID,f.get(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID)).set(WAITING_CONDITIONS.CONDITION_TYPE,WaitingConditionType.SCHEDULED_TIME).set(WAITING_CONDITIONS.CONDITION_PAYLOAD,payload).set(WAITING_CONDITIONS.REASON,"납기 후 실제 입고 및 LOT 확인").execute();
            }
            return dsl.insertInto(EVENTS).set(EVENTS.EVENT_TYPE,"REPLENISHMENT_FOLLOWUP_CHANGED").set(EVENTS.CASE_ID,f.get(REPLENISHMENT_FOLLOWUPS.CASE_ID)).set(EVENTS.WORK_ITEM_ID,f.get(REPLENISHMENT_FOLLOWUPS.WORK_ITEM_ID)).set(EVENTS.PAYLOAD,JSONB.valueOf(json.write(Map.of("followupRef",f.get(REPLENISHMENT_FOLLOWUPS.FOLLOWUP_REF),"observation",observation)))).returning(EVENTS.EVENT_ID).fetchSingle().get(EVENTS.EVENT_ID);
        }
        return null;
    }
    static String ref(String prefix) { return prefix+"-"+UUID.randomUUID().toString().replace("-","").substring(0,14); }
    record Source(long caseId,long workId,long planId,Long parentId,Long applicationId,String outcome) {}
    record ReceiptLine(long itemId,BigDecimal ordered,LocalDate date,Map<Long,Receipt> receipts) {}
    record Receipt(long id,BigDecimal quantity,String status,boolean identityMatches,List<Lot> lots) {}
    record Lot(long id,BigDecimal quantity,boolean identityMatches,BigDecimal remainingQuantity) {}
}
