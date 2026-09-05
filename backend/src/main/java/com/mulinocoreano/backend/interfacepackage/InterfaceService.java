package com.mulinocoreano.backend.interfacepackage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 인터페이스 메커니즘 (docs/08_interface_overview.md) 구현.
 *
 * Query(ASK)는 Case를 만들지 않는다. ACT만 Case를 생성한다.
 * Waiting은 이벤트 충족으로 해소된다 — 이 서비스는 그 상태 전이를 기록한다.
 */
@Service
public class InterfaceService {

    private static final int INVENTORY_RESULT_LIMIT = 20;
    private static final String DEFAULT_CHANNEL_REF = "SYSTEM_DEFAULT";
    private static final Set<String> SUPPORTED_CHANNELS =
            Set.of("CHAT", "SLACK", "EMAIL", "DASHBOARD", "API");

    private final JdbcClient jdbc;
    private final RunService runService;

    public InterfaceService(JdbcClient jdbc, RunService runService) {
        this.jdbc = jdbc;
        this.runService = runService;
    }

    // ------------------------------------------------------------------ ASK
    public AskResponse ask(String productQuery) {
        String query = normalizeSearchTerm(productQuery);
        String filter = query == null ? "" : """
                WHERE POSITION(LOWER(:query) IN LOWER(p.name)) > 0
                   OR POSITION(LOWER(:query) IN LOWER(p.sku)) > 0
                """;
        String sql = """
                SELECT p.name, p.sku, s.quantity, w.name,
                       count(*) OVER () AS total_locations
                FROM stock s
                JOIN products p ON p.product_id = s.product_id
                JOIN warehouses w ON w.warehouse_id = s.warehouse_id
                %s
                ORDER BY p.name, p.sku, w.name, w.warehouse_id
                LIMIT %d
                """.formatted(filter, INVENTORY_RESULT_LIMIT);
        var statement = jdbc.sql(sql);
        if (query != null) {
            statement = statement.param("query", query);
        }
        List<InventorySearchRow> rows = statement
                .query((rs, i) -> new InventorySearchRow(
                        new InventoryDto(rs.getString(1), rs.getString(2),
                                rs.getBigDecimal(3), rs.getString(4)),
                        rs.getLong(5)))
                .list();
        List<InventoryDto> inventory = rows.stream().map(InventorySearchRow::inventory).toList();
        long totalLocations = rows.isEmpty() ? 0 : rows.get(0).totalLocations();
        boolean truncated = totalLocations > inventory.size();

        String subject = query == null ? "전체 완제품" : "'" + query + "' 검색";
        String answer = totalLocations == 0
                ? subject + " 재고 위치가 없습니다."
                : subject + " 결과: 재고 위치 총 " + totalLocations + "건 중 "
                  + inventory.size() + "건을 반환했습니다."
                  + (truncated ? " 결과는 " + INVENTORY_RESULT_LIMIT + "건으로 제한됩니다." : "");

        return new AskResponse(answer, "ASK", query, inventory,
                totalLocations, inventory.size(), truncated,
                "sources=stock,products,warehouses;generated_by=inventory_search");
    }

    // ------------------------------------------------------------------ ACT
    @Transactional
    public CaseDto createCase(CreateCaseRequest req) {
        ValidatedCaseRequest request = validateCaseRequest(req);
        String caseRef = newPublicRef("CASE");
        String title = truncate(request.objective(), 60);

        // 기본 담당 = orchestrator 로 시작 (다중 배정은 UI/API로 확장)
        long agentId = jdbc.sql("""
                        SELECT agent_id FROM agents
                        WHERE agent_key='ORCHESTRATOR' AND is_active=true
                        FOR SHARE
                        """)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> unavailable(
                        "No active ORCHESTRATOR agent is configured"));
        long channelId = jdbc.sql("""
                        SELECT channel_id FROM channels
                        WHERE channel_type=:channel::channel_type
                          AND external_ref=:externalRef
                        """)
                .param("channel", request.channel())
                .param("externalRef", DEFAULT_CHANNEL_REF)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> unavailable(
                        "No default channel is configured for " + request.channel()));

        jdbc.sql("""
                INSERT INTO cases (case_ref, title, objective, intent_type, origin_channel_id)
                VALUES (:ref, :title, :obj, 'ACT', :channelId)
                """)
                .param("ref", caseRef).param("title", title)
                .param("obj", request.objective())
                .param("channelId", channelId)
                .update();

        Long caseId = jdbc.sql("SELECT case_id FROM cases WHERE case_ref=:r")
                .param("r", caseRef).query(Long.class).single();

        jdbc.sql("""
                INSERT INTO case_participants (case_id, actor_type, agent_id)
                VALUES (:cid, 'AGENT', :aid)
                """)
                .param("cid", caseId).param("aid", agentId).update();

        // 초기 Work Item 1건: 목표 분해
        String wiRef = newPublicRef("WI");
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
                  (SELECT count(*)
                   FROM cases c
                   WHERE c.status IN ('OPEN','IN_PROGRESS','WAITING')
                     AND (
                       EXISTS (
                         SELECT 1 FROM work_items wi
                         WHERE wi.case_id=c.case_id
                           AND wi.status NOT IN ('DONE','CANCELLED')
                           AND wi.due_at < CURRENT_TIMESTAMP
                       )
                       OR EXISTS (
                         SELECT 1 FROM attention_requests ar
                         WHERE ar.case_id=c.case_id
                           AND ar.status='OPEN'
                           AND ar.reason_type='MATERIAL_EXCEPTION'
                       )
                     )) AS cases_at_risk,
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
    private ValidatedCaseRequest validateCaseRequest(CreateCaseRequest request) {
        if (request == null || request.objective() == null || request.objective().isBlank()) {
            throw new InvalidInterfaceRequestException("objective is required");
        }
        if (request.intentType() != null && !"ACT".equals(request.intentType())) {
            throw new InvalidInterfaceRequestException("intentType must be ACT when supplied");
        }
        String channel = request.channel() == null ? "CHAT" : request.channel();
        if (!SUPPORTED_CHANNELS.contains(channel)) {
            throw new InvalidInterfaceRequestException("channel is invalid");
        }
        return new ValidatedCaseRequest(request.objective().trim(), channel);
    }

    private String normalizeSearchTerm(String query) {
        return query == null || query.isBlank() ? null : query.trim();
    }

    private String newPublicRef(String prefix) {
        int randomLength = 18 - prefix.length();
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, randomLength);
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    private String truncate(String s, int len) {
        return s.length() <= len ? s : s.substring(0, len - 1) + "…";
    }

    private record InventorySearchRow(InventoryDto inventory, long totalLocations) {
    }

    private record ValidatedCaseRequest(String objective, String channel) {
    }
}
