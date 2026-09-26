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

    static Map<String, Object> summarize(List<JsonNode> modelFinished) {
        double cost = 0; long in = 0, out = 0; List<String> failures = new ArrayList<>();
        for (JsonNode e : modelFinished) {
            cost += e.path("costUsd").asDouble(0);
            in += e.path("inputTokens").asLong(0);
            out += e.path("outputTokens").asLong(0);
            if (e.hasNonNull("failure")) failures.add(e.get("failure").asText());
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("runs", modelFinished.size()); s.put("costUsd", cost); s.put("inputTokens", in); s.put("outputTokens", out); s.put("failures", failures);
        return s;
    }

    static Path write(ObjectMapper mapper, Path dir, String tcId, Map<String, Object> record) throws java.io.IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(tcId + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        return file;
    }
}
