package com.mulinocoreano.backend.interfacepackage;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 인터페이스 메커니즘 REST API — CLI와 대시보드(ChatGPT 커넥터)의 단일 진입점.
 * 모든 쓰기 호출은 추후 L1 거버넌스 인터셉터를 통과한다 (현재는 READ/Case 생성만 개방).
 */
@RestController
@RequestMapping("/api/v1")
public class InterfaceController {

    private final InterfaceService service;

    public InterfaceController(InterfaceService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ ASK
    @GetMapping("/ask")
    public AskResponse ask(@RequestParam String q) {
        return service.ask(q);
    }

    // ------------------------------------------------------------ ACT
    @PostMapping("/cases")
    public CaseDto createCase(@RequestBody CreateCaseRequest req) {
        return service.createCase(req);
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
    public RunDto createRun(@RequestBody CreateRunRequest req) {
        return service.createRun(req);
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
