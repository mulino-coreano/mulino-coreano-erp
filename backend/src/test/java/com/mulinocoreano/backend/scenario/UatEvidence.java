package com.mulinocoreano.backend.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** UAT는 실행마다 비용·토큰·실패 코드를 합산해 남기고, 준비되지 않은 환경에서는 모델을 부르지 않는다
 * (목표 5: 에이전트는 어떤 하네스/모델에서도 자기 역할만 수행하고, 그 밖에는 아무것도 쓰지 않는다). */
public final class UatEvidence {
    static final List<String> REQUIRED = List.of("MULINO_AGENT_RUNTIME", "MULINO_AGENT_MODEL", "MULINO_RUNTIME_IMAGE", "MULINO_AUTH_VOLUME");
    private UatEvidence() {}

    static List<String> prerequisitesMissing(Map<String, String> env) {
        return REQUIRED.stream().filter(k -> env.getOrDefault(k, "").isBlank()).toList();
    }

    /** 이 Case의 DB Run 목록(agent_key, run_ref, status, outcome)에 러너의 model_finished 로그
     * (run_ref로 짝지은 비용·토큰·실패 코드)를 합쳐 Run별 결과 배열을 만들고, 전체 합계도 함께
     * 남긴다. 한 run_ref에 model_finished가 여러 번 남을 수 있다 -- lease 만료 뒤 재청구된 Run은
     * 이전 시도의 model_finished도 로그에 남기 때문에 -- 그래서 비용·토큰은 합산하고, 실패 코드는
     * 가장 마지막 것을 남긴다. */
    static Map<String, Object> summarize(List<BusinessState.RunRecord> dbRuns, List<JsonNode> modelFinished) {
        Map<String, List<JsonNode>> byRunRef = new LinkedHashMap<>();
        for (JsonNode e : modelFinished) {
            String runRef = e.path("runRef").asText(null);
            if (runRef == null) continue; // model_finished always carries runRef (agents/runner/src/runner.js); defensive only.
            byRunRef.computeIfAbsent(runRef, k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> runs = new ArrayList<>();
        double totalCost = 0; long totalIn = 0, totalOut = 0; List<String> failures = new ArrayList<>();
        for (BusinessState.RunRecord r : dbRuns) {
            double cost = 0; long in = 0, out = 0; String failure = null;
            for (JsonNode e : byRunRef.getOrDefault(r.runRef(), List.of())) {
                cost += e.path("costUsd").asDouble(0);
                in += e.path("inputTokens").asLong(0);
                out += e.path("outputTokens").asLong(0);
                if (e.hasNonNull("failure")) failure = e.get("failure").asText();
            }
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("agentKey", r.agentKey());
            run.put("runRef", r.runRef());
            run.put("status", r.status());
            run.put("outcome", r.outcome());
            run.put("failure", failure);
            run.put("costUsd", cost);
            run.put("inputTokens", in);
            run.put("outputTokens", out);
            runs.add(run);

            if (failure != null) failures.add(failure);
            totalCost += cost; totalIn += in; totalOut += out;
        }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("runs", runs);
        s.put("costUsd", totalCost);
        s.put("inputTokens", totalIn);
        s.put("outputTokens", totalOut);
        s.put("failures", failures);
        return s;
    }

    static Path write(ObjectMapper mapper, Path dir, String tcId, Map<String, Object> record) throws java.io.IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(tcId + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        return file;
    }
}
