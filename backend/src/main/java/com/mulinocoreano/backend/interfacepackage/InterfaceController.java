package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 인터페이스 메커니즘 REST API — CLI와 대시보드(ChatGPT 커넥터)의 단일 진입점. 인간 조회·Case 접수와 내부 서비스의 Run/디스패처 쓰기는 공통 보안
 * 계층에서 분리한다. 구매 승인·발주 API는 PurchaseController에서 담당한다.
 */
@RestController
@RequestMapping("/api/v1")
public class InterfaceController {

    private final CaseIntakeService intake;
    private final InterfaceQueries queries;
    private final RunService runs;
    private final DispatcherService dispatcher;
    private final CaseOverviewService overview;

    public InterfaceController(
            CaseIntakeService intake,
            InterfaceQueries queries,
            RunService runs,
            DispatcherService dispatcher,
            CaseOverviewService overview) {
        this.intake = intake;
        this.queries = queries;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.overview = overview;
    }

    // ------------------------------------------------------------ ASK
    @GetMapping("/ask")
    public AskResponse ask(@RequestParam(required = false) String q) {
        return queries.ask(q);
    }

    // ------------------------------------------------------------ ACT
    @PostMapping("/cases")
    public CaseDto createCase(
            @Valid @RequestBody CreateCaseRequest req,
            @RequestHeader("Idempotency-Key") String key) {
        return intake.createCase(req, key);
    }

    @GetMapping("/cases")
    public List<CaseSearchDto> listCases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String productSku) {
        return overview.search(q, productSku, status);
    }

    @GetMapping("/cases/{caseRef}/overview")
    public Map<String, Object> overview(@PathVariable String caseRef) {
        return overview.overview(caseRef);
    }

    @GetMapping("/cases/{caseRef}")
    public CaseDto getCase(@PathVariable String caseRef) {
        return queries.getCase(caseRef);
    }

    @GetMapping("/cases/{caseRef}/work-items")
    public List<WorkItemDto> listWorkItems(@PathVariable String caseRef) {
        return queries.listWorkItems(caseRef);
    }

    // ------------------------------------------------------------ Execution (Run)
    @PostMapping("/runs")
    public RunDto createRun(@Valid @RequestBody CreateRunRequest req) {
        return runs.createRun(req, null);
    }

    // ------------------------------------------------------------ Events / Dispatcher
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventDispatchResponse ingestEvent(@Valid @RequestBody CreateEventRequest req) {
        return dispatcher.ingest(req);
    }

    @PostMapping("/dispatch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventDispatchResponse dispatch() {
        return dispatcher.dispatchScheduled();
    }

    @GetMapping("/events")
    public List<EventDto> listEvents(@RequestParam(required = false) String caseRef) {
        return dispatcher.listEvents(caseRef);
    }

    // ------------------------------------------------------------ Attention
    @GetMapping("/attention")
    public List<AttentionDto> attention() {
        return queries.listAttention();
    }

    // ------------------------------------------------------------ Monitor
    @GetMapping("/monitor")
    public MonitorDto monitor() {
        return queries.monitor();
    }

    // ------------------------------------------------------------ health
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "layer", "interface");
    }
}
