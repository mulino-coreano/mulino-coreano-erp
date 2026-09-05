package com.mulinocoreano.backend.interfacepackage;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 인터페이스 메커니즘 REST API — CLI와 대시보드(ChatGPT 커넥터)의 단일 진입점.
 * 인간 조회·Case 접수와 내부 서비스의 Run/디스패처 쓰기는 공통 보안 계층에서 분리한다.
 * 실제 ERP 변경 및 승인 adapter는 후속 L1 구현 범위다.
 */
@RestController
@RequestMapping("/api/v1")
public class InterfaceController {

    private final InterfaceService service;
    private final DispatcherService dispatcher;

    public InterfaceController(InterfaceService service, DispatcherService dispatcher) {
        this.service = service;
        this.dispatcher = dispatcher;
    }

    // ------------------------------------------------------------ ASK
    @GetMapping("/ask")
    public AskResponse ask(@RequestParam(required = false) String q) {
        return service.ask(q);
    }

    // ------------------------------------------------------------ ACT
    @PostMapping("/cases")
    public CaseDto createCase(@Valid @RequestBody CreateCaseRequest req, @RequestHeader("Idempotency-Key") String key) {
        return service.createCase(req, key);
    }

    @GetMapping("/cases")
    public List<CaseDto> listCases(@RequestParam(required = false) String status) {
        return service.listCases(status);
    }

    @GetMapping("/cases/{caseRef}")
    public CaseDto getCase(@PathVariable String caseRef) {
        return service.getCase(caseRef);
    }

    @GetMapping("/cases/{caseRef}/work-items")
    public List<WorkItemDto> listWorkItems(@PathVariable String caseRef) {
        return service.listWorkItems(caseRef);
    }

    // ------------------------------------------------------------ Execution (Run)
    @PostMapping("/runs")
    public RunDto createRun(@Valid @RequestBody CreateRunRequest req) {
        return service.createRun(req);
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
        return service.listAttention();
    }

    // ------------------------------------------------------------ Monitor
    @GetMapping("/monitor")
    public MonitorDto monitor() {
        return service.monitor();
    }

    // ------------------------------------------------------------ health
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "layer", "interface");
    }
}
