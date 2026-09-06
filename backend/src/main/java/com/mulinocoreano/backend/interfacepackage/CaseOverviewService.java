package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.generated.enums.CaseStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CaseOverviewService {
    private final InterfaceQueries queries;
    private final CaseOverviewRepository repository;

    public CaseOverviewService(InterfaceQueries queries, CaseOverviewRepository repository) {
        this.queries = queries;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> overview(String ref) {
        var c = queries.getCase(ref);
        var work = repository.workItems(c.caseId());
        var remaining =
                work.stream()
                        .filter(w -> !Set.of("DONE", "CANCELLED").contains(w.get("status")))
                        .toList();
        var attention = repository.attention(c.caseId());
        var questions =
                attention.stream()
                        .filter(a -> "OPEN".equals(a.get("status")))
                        .map(a -> a.get("question").toString())
                        .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", c);
        result.put("summary", CaseOverviewRepository.summary(c.status(), remaining, questions));
        result.put("participants", repository.participants(c.caseId()));
        result.put("workItems", work);
        result.put("attention", attention);
        result.put("plans", repository.plans(c.caseId()));
        result.put("approvals", repository.approvals(c.caseId()));
        result.put("decisions", repository.decisions(c.caseId()));
        result.put("evidence", repository.evidence(c.caseId()));
        result.put("claims", repository.claims(c.caseId()));
        result.put("timeline", repository.timeline(c.caseId()));
        result.put("remainingObligations", remaining);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CaseSearchDto> search(String q, String sku, String status) {
        if (q != null && q.length() > 200)
            throw new InvalidInterfaceRequestException("q must be at most 200 characters");
        if (sku != null && (sku.isBlank() || sku.length() > 50))
            throw new InvalidInterfaceRequestException("productSku must contain 1..50 characters");
        CaseStatus parsed = null;
        try {
            if (status != null) parsed = CaseStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new InvalidInterfaceRequestException("Invalid Case status");
        }
        var cases = repository.search(q, sku, parsed);
        var summaries = repository.summaries(cases);
        return cases.stream()
                .map(
                        c ->
                                new CaseSearchDto(
                                        c.caseId(),
                                        c.caseRef(),
                                        c.title(),
                                        c.objective(),
                                        c.status(),
                                        c.intentType(),
                                        c.openedAt(),
                                        c.metadata(),
                                        c.reused(),
                                        summaries.get(c.caseId())))
                .toList();
    }
}
