package com.mulinocoreano.backend.interfacepackage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 인터페이스 메커니즘 (docs/08_interface_overview.md) 구현.
 *
 * Query(ASK)는 Case를 만들지 않는다. ACT만 Case를 생성한다.
 * Waiting은 이벤트 충족으로 해소된다 — 이 서비스는 그 상태 전이를 기록한다.
 */
@Service
public class InterfaceService {

    private final JdbcClient jdbc;
    private final RunService runService;

    public InterfaceService(JdbcClient jdbc, RunService runService) {
        this.jdbc = jdbc;
        this.runService = runService;
    }

    // ------------------------------------------------------------------ ASK
    public AskResponse ask(String question) {
        List<InventoryDto> inventory = jdbc.sql("""
                SELECT p.name, p.sku, s.quantity, w.name
                FROM stock s
                JOIN products p ON p.product_id = s.product_id
                JOIN warehouses w ON w.warehouse_id = s.warehouse_id
                ORDER BY p.name
                LIMIT 20
                """)
                .query((rs, i) -> new InventoryDto(
                        rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3), rs.getString(4)))
                .list();

        String answer = inventory.isEmpty()
                ? "등록된 완제품 재고가 없습니다. (조회 시간: 시스템 시각)"
                : "현재 완제품 재고는 총 " + inventory.size() + "개 제품군이 있습니다. 첫 항목: "
                  + inventory.get(0).productName() + " " + inventory.get(0).quantity() + " " + inventory.get(0).warehouseName();

        return new AskResponse(answer, "ASK", inventory,
                "sources=stock,products,warehouses;generated_by=ask_capability");
    }

    // ------------------------------------------------------------------ ACT
    @Transactional
    public CaseDto createCase(CreateCaseRequest req) {
        String caseRef = nextRef("cases", "CASE");
        String channel = req.channel() == null ? "CHAT" : req.channel();
        String title = truncate(req.objective(), 60);

        // 기본 담당 = orchestrator 로 시작 (다중 배정은 UI/API로 확장)
        Long agentId = jdbc.sql("SELECT agent_id FROM agents WHERE agent_key='ORCHESTRATOR'")
                .query(Long.class).optional().orElse(null);

        jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type, origin_channel_id)
                VALUES (:ref, :title, :obj, :intent::intent_type,
                        (SELECT channel_id FROM channels WHERE channel_type=:ch::channel_type LIMIT 1))
                """)
                .param("ref", caseRef).param("title", title)
                .param("obj", req.objective())
                .param("intent", req.intentType() == null ? "ACT" : req.intentType())
                .param("ch", channel)
                .update();

        Long caseId = jdbc.sql("SELECT case_id FROM cases WHERE case_ref=:r")
                .param("r", caseRef).query(Long.class).single();

        if (agentId != null) {
            jdbc.sql("""
                    INSERT INTO case_participants (case_id, actor_type, agent_id)
                    VALUES (:cid, 'AGENT', :aid)
                    """)
                    .param("cid", caseId).param("aid", agentId).update();
        }

        // 초기 Work Item 1건: 목표 분해
        String wiRef = nextRef("work_items", "WI");
        jdbc.sql("""
                INSERT INTO work_items (work_item_ref, case_id, title, status, assigned_agent_id)
                VALUES (:ref, :cid, '목표 분해 및 계획 수립', 'READY', :aid)
                """)
                .param("ref", wiRef).param("cid", caseId).param("aid", agentId).update();

        return getCase(caseRef);
    }

    public CaseDto getCase(String caseRef) {
        return jdbc.sql("""
                SELECT case_id, case_ref, title, objective, status::text, intent_type::text, opened_at
                FROM cases WHERE case_ref=:r
                """)
                .param("r", caseRef)
                .query((rs, i) -> new CaseDto(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()))
                .single();
    }

    public List<CaseDto> listCases(String statusFilter) {
        String sql = """
                SELECT case_id, case_ref, title, objective, status::text, intent_type::text, opened_at
                FROM cases
                """ + (statusFilter == null ? "ORDER BY opened_at DESC" : "WHERE status=:st::case_status ORDER BY opened_at DESC");
        var spec = jdbc.sql(sql);
        if (statusFilter != null) spec = spec.param("st", statusFilter);
        return spec.query((rs, i) -> new CaseDto(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant())).list();
    }

    // ------------------------------------------------------------------ Work Items
    public List<WorkItemDto> listWorkItems(String caseRef) {
        return jdbc.sql("""
                SELECT w.work_item_id, w.work_item_ref, w.title, w.status::text,
                       COALESCE(a.display_name, '(미배정)') AS agent_name,
                       wc.reason AS waiting_reason,
                       w.due_at
                FROM work_items w
                JOIN cases c ON c.case_id = w.case_id
                LEFT JOIN agents a ON a.agent_id = w.assigned_agent_id
                LEFT JOIN LATERAL (
                    SELECT reason FROM waiting_conditions x
                    WHERE x.work_item_id = w.work_item_id AND x.status='ACTIVE'
                    ORDER BY created_at DESC LIMIT 1
                ) wc ON TRUE
                WHERE c.case_ref = :r
                ORDER BY w.work_item_id
                """)
                .param("r", caseRef)
                .query((rs, i) -> new WorkItemDto(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ Runs (일회용 실행)
    public RunDto createRun(CreateRunRequest req) {
        return runService.createRun(req, null);
    }

    // ------------------------------------------------------------------ Attention (인간 주의)
    public List<AttentionDto> listAttention() {
        return jdbc.sql("""
                SELECT ar.attention_request_id, c.case_ref, ar.reason_type::text, ar.title,
                       ar.question, ar.consequence, ar.status::text, ar.created_at
                FROM attention_requests ar JOIN cases c ON c.case_id = ar.case_id
                WHERE ar.status='OPEN'
                ORDER BY ar.created_at DESC
                """)
                .query((rs, i) -> new AttentionDto(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ Monitor (대시보드)
    public MonitorDto monitor() {
        Map<String, Long> counts = jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM cases WHERE status IN ('OPEN','IN_PROGRESS')) AS cases_open,
                  (SELECT count(*) FROM cases WHERE status='WAITING') AS cases_waiting,
                  (SELECT count(*) FROM work_items WHERE status='READY') AS wi_ready,
                  (SELECT count(*) FROM work_items WHERE status='WAITING') AS wi_waiting,
                  (SELECT count(*) FROM attention_requests WHERE status='OPEN') AS attn_open
                """)
                .query((rs, i) -> Map.of(
                        "cases_open", rs.getLong(1),
                        "cases_at_risk", rs.getLong(2),
                        "wi_ready", rs.getLong(3),
                        "wi_waiting", rs.getLong(4),
                        "attn_open", rs.getLong(5)))
                .single();

        return new MonitorDto(
                counts.get("cases_open"), counts.get("cases_at_risk"),
                counts.get("wi_ready"), counts.get("wi_waiting"), counts.get("attn_open"),
                listAttention().stream().limit(5).toList());
    }

    // ------------------------------------------------------------------ helpers
    private String nextRef(String table, String prefix) {
        Long n = jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
        return prefix + "-" + (1900 + n);
    }

    private String truncate(String s, int len) {
        return s.length() <= len ? s : s.substring(0, len - 1) + "…";
    }
}
