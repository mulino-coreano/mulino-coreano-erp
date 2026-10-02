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
import java.util.UUID;

/** Case와 최초 책임을 같은 트랜잭션에서 생성한다. */
@Service
public class CaseIntakeService {
    private static final String DEFAULT_CHANNEL_REF = "SYSTEM_DEFAULT";
    private static final Set<String> SUPPORTED_CHANNELS = Set.of("CHAT", "SLACK", "EMAIL", "DASHBOARD", "API");
    private final JdbcClient jdbc;
    private final RequestIdempotency idempotency;
    public CaseIntakeService(JdbcClient jdbc, RequestIdempotency idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    @Transactional
    public CaseDto createCase(CreateCaseRequest input, String key) {
        var request = validateCaseRequest(input);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return insert(request, null);
        }
        if (!auth.isAuthenticated() || !(auth.getPrincipal() instanceof HumanActor human)
                || !human.capabilities().contains("work:write")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Human work delegation required");
        }
        var allowed = jdbc.sql("SELECT user_id FROM users WHERE user_id=:id AND is_active=true AND role IN ('MANAGER','OPERATOR') FOR SHARE")
                .param("id", human.userId()).query(Long.class).optional();
        if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Delegating user is not active");
        if (key == null) return insert(request, human.userId());
        return idempotency.execute("case.create:" + human.userId(), key, request,
                () -> insert(request, human.userId()));
    }

    private CaseDto insert(ValidatedCaseRequest request, Long userId) {
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
