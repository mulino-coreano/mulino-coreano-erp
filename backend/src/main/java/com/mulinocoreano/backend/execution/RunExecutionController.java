package com.mulinocoreano.backend.execution;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/runs")
public class RunExecutionController {
    private final RunExecutionService execution;
    public RunExecutionController(RunExecutionService execution) { this.execution=execution; }
    @PostMapping("/claim") public ResponseEntity<RunExecutionService.Claim> claim(@RequestBody ClaimRequest request) {
        return execution.claim(request.workerId()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());
    }
    @PostMapping("/heartbeat") public RunExecutionService.Receipt heartbeat(@RequestBody LeaseRequest request) {
        return execution.heartbeat(request.runRef(),request.workerId(),request.leaseToken());
    }
    @PostMapping("/finish") public RunExecutionService.Receipt finish(@RequestBody FinishRequest request) {
        return execution.finish(request.runRef(),request.workerId(),request.leaseToken(),request.outcome(),request.summary(),request.waitingConditions());
    }
    @PostMapping("/retry") public Map<String,Object> retry(@RequestBody LeaseRequest request) {
        return execution.retry(request.runRef(),request.workerId(),request.leaseToken());
    }
    public record ClaimRequest(String workerId) {}
    public record LeaseRequest(String runRef,String workerId,String leaseToken) {}
    public record FinishRequest(String runRef,String workerId,String leaseToken,String outcome,String summary,List<RunExecutionService.Wait> waitingConditions) {}
}
