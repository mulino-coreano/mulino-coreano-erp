package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.idempotency.RequestIdempotency;
import com.mulinocoreano.backend.security.HumanActor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import java.util.Map;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.UUID;

/** Case와 최초 책임을 같은 트랜잭션에서 생성한다. */
@Service
public class CaseIntakeService {
    private static final String DEFAULT_CHANNEL_REF = "SYSTEM_DEFAULT";
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("CHAT", "SLACK", "EMAIL", "DASHBOARD", "API");
    private final RunService runs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final RequestIdempotency idempotency;
    public CaseIntakeService(JdbcClient jdbc, RequestIdempotency idempotency, RunService runs,
                             ObjectMapper mapper, @Qualifier("planningClock") Clock clock) {
        this.runs = runs;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    @Transactional
    public CaseDto createCase(CreateCaseRequest input, String key) {
        var request = validateCaseRequest(input);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            if (input.replenishment() != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Human replenishment delegation required");
            return insert(request, null, null);
        }
        if (!auth.isAuthenticated() || !(auth.getPrincipal() instanceof HumanActor human)
                || !human.capabilities().contains("work:write")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Human work delegation required");
        }
        var allowed = jdbc.sql("SELECT user_id FROM users WHERE user_id=:id AND is_active=true AND role IN ('MANAGER','OPERATOR') FOR SHARE")
                .param("id", human.userId()).query(Long.class).optional();
        if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Delegating user is not active");
        var scope = normalizeReplenishment(input.replenishment());
        if (key == null) return insert(request, human.userId(), resolveReplenishment(scope));
        if (scope == null) {
            // Keep the original two-field receipt hash for existing generic Case requests.
            return idempotency.execute("case.create:" + human.userId(), key, request,
                    () -> insert(request, human.userId(), null));
        }
        var receipt = idempotency.executeCanonicalJson("case.create:" + human.userId(), key,
                Map.of("request", request, "replenishment", scope),
                () -> insert(request, human.userId(), resolveReplenishment(scope)));
        return mapper.treeToValue(receipt, CaseDto.class);
    }

    private CaseDto insert(ValidatedCaseRequest request, Long userId, Map<String, Object> target) {
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
                INSERT INTO cases (case_ref, title, objective, intent_type, origin_channel_id, opened_by_user_id)
                VALUES (:ref, :title, :obj, 'ACT', :channelId, :userId)
                """)
                .param("ref", caseRef).param("title", title)
                .param("obj", request.objective())
                .param("channelId", channelId)
                .param("userId", userId, java.sql.Types.BIGINT)
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

        if (target != null) {
            jdbc.sql("UPDATE cases SET metadata=cast(:metadata AS jsonb) WHERE case_id=:id")
                .param("metadata", mapper.writeValueAsString(Map.of("replenishment", target))).param("id", caseId).update();
            // The partial unique index arbitrates competing intakes without aborting SQL.
            int bound = jdbc.sql("""
                    INSERT INTO planning_cases(case_id,warehouse_id) VALUES (:id,:warehouse)
                    ON CONFLICT (warehouse_id) WHERE status='ACTIVE' DO NOTHING
                    """)
                .param("id", caseId).param("warehouse", target.get("warehouseId")).update();
            if (bound == 0) {
                // Roll back this entire intake, including its Case, Work and receipt.
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "An ACTIVE planning Case already exists for this warehouse");
            }
            var queued = runs.createRun(new CreateRunRequest("ORCHESTRATOR", caseRef, wiRef, runs.defaultRuntime()), null);
            if (!"QUEUED".equals(queued.status())) throw unavailable("Initial Run could not be queued");
        }
        if (userId != null) {
            jdbc.sql("INSERT INTO case_participants(case_id,actor_type,user_id) VALUES (:id,'USER',:user)")
                    .param("id", caseId).param("user", userId).update();
        }
        return jdbc.sql("SELECT case_id,case_ref,title,objective,status::text,intent_type::text,opened_at FROM cases WHERE case_id=:id")
                .param("id", caseId).query((rs, n) -> new CaseDto(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant())).single();
    }

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

    private CreateCaseRequest.Replenishment normalizeReplenishment(CreateCaseRequest.Replenishment target) {
        if (target == null) return null;
        if (target.warehouseId() == null || target.warehouseId() <= 0
                || target.productSkus() == null || target.productSkus().isEmpty()
                || target.productSkus().size() > 100
                || target.productSkus().stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 50))
            throw new InvalidInterfaceRequestException("Invalid replenishment scope");
        return new CreateCaseRequest.Replenishment(target.productSkus().stream()
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList(),
                target.warehouseId(), target.targetDate());
    }

    private Map<String, Object> resolveReplenishment(CreateCaseRequest.Replenishment target) {
        if (target == null) return null;
        var skus = target.productSkus();
        var horizon = jdbc.sql("SELECT horizon_days FROM planning_policies WHERE warehouse_id=:id").param("id", target.warehouseId()).query(Integer.class).optional().orElseThrow(() -> new InvalidInterfaceRequestException("Warehouse needs a planning policy"));
        var products = jdbc.sql("SELECT product_id,sku FROM products WHERE upper(sku) IN (:skus) AND is_active=true AND product_type='FINISHED_GOODS' ORDER BY product_id").param("skus", skus).query((rs,n) -> new Product(rs.getLong(1),rs.getString(2))).list();
        if (products.size() != skus.size()) throw new InvalidInterfaceRequestException("Every SKU must identify an active finished product");
        var today = LocalDate.now(clock);
        var end = target.targetDate() == null ? today.plusDays(horizon - 1L) : target.targetDate();
        long days = ChronoUnit.DAYS.between(today,end)+1;
        if (days < 1 || days > 90) throw new InvalidInterfaceRequestException("Target date must be within 1..90 days");
        return Map.of("warehouseId",target.warehouseId(),"productIds",products.stream().map(Product::id).toList(),"productSkus",products.stream().map(Product::sku).toList(),"targetDate",end.toString());
    }
    private record Product(long id, String sku) {}

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

    private record ValidatedCaseRequest(String objective, String channel) {}
}
