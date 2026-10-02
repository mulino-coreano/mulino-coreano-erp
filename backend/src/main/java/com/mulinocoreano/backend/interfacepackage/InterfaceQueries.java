package com.mulinocoreano.backend.interfacepackage;

import com.mulinocoreano.backend.generated.enums.CaseStatus;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Read-side presentation and query limits for the employee interface. */
@Service
public class InterfaceQueries {
    private static final int INVENTORY_RESULT_LIMIT = 20;
    private final InterfaceReadRepository repository;

    public InterfaceQueries(InterfaceReadRepository repository) {
        this.repository = repository;
    }

    public AskResponse ask(String productQuery) {
        String query = productQuery == null || productQuery.isBlank() ? null : productQuery.trim();
        var result = repository.inventory(query, INVENTORY_RESULT_LIMIT);
        var inventory = result.locations();
        long total = result.totalLocations();
        boolean truncated = total > inventory.size();
        String subject = query == null ? "전체 완제품" : "'" + query + "' 검색";
        String answer =
                total == 0
                        ? subject + " 재고 위치가 없습니다."
                        : subject
                                + " 결과: 재고 위치 총 "
                                + total
                                + "건 중 "
                                + inventory.size()
                                + "건을 반환했습니다."
                                + (truncated
                                        ? " 결과는 " + INVENTORY_RESULT_LIMIT + "건으로 제한됩니다."
                                        : "");
        return new AskResponse(
                answer,
                "ASK",
                query,
                inventory,
                total,
                inventory.size(),
                truncated,
                "sources=stock,products,warehouses;generated_by=inventory_search");
    }

    public CaseDto getCase(String caseRef) {
        return repository
                .findCase(caseRef)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
    }

    public List<CaseDto> listCases(String status) {
        return repository.cases(status == null ? null : CaseStatus.valueOf(status));
    }

    public List<WorkItemDto> listWorkItems(String caseRef) {
        return repository.workItems(caseRef);
    }

    public List<AttentionDto> listAttention() {
        return repository.attention(null);
    }

    public MonitorDto monitor() {
        var counts = repository.counts();
        return new MonitorDto(
                counts.casesOpen(),
                counts.casesAtRisk(),
                counts.ready(),
                counts.waiting(),
                counts.attention(),
                repository.attention(5));
    }
}
