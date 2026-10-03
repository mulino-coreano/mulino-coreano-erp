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

    /** Metadata-only checks: never read login contents or attempt authentication. */
    static List<String> infrastructureMissing(Map<String,String> env) {
        List<String> missing = new ArrayList<>();
        for (String kind : List.of("image","volume")) {
            String target=env.get(kind.equals("image") ? "MULINO_RUNTIME_IMAGE" : "MULINO_AUTH_VOLUME");
            try {
                var pb=new ProcessBuilder("docker",kind,"inspect",target);
                pb.environment().keySet().retainAll(java.util.Set.of("PATH","HOME"));
                if (env.containsKey("DOCKER_HOST")) pb.environment().put("DOCKER_HOST",env.get("DOCKER_HOST"));
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
                var process=pb.start();
                if (!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();missing.add(kind);}
                else if (process.exitValue()!=0) missing.add(kind);
            } catch (java.io.IOException e) {missing.add("docker");break;}
            catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
        return missing;
    }

    static void verifyContainerRoute(Map<String,String> env, String apiBase) throws Exception {
        var url=java.net.URI.create(apiBase);
        if (url.getPort()<=0) throw new AssertionError("Container API must use the actual Spring port");
        var pb=new ProcessBuilder("docker","run","--rm","--read-only","--cap-drop=ALL",
            "--security-opt=no-new-privileges","--user=10001:10001","--entrypoint=mulino",
            "--env","MULINO_API_URL="+apiBase,"--env","MULINO_TOKEN=noncredential-route-probe",
            env.get("MULINO_RUNTIME_IMAGE"),"case","show","CASE-ROUTE-PROBE");
        pb.environment().keySet().retainAll(java.util.Set.of("PATH","HOME"));
        if (env.containsKey("DOCKER_HOST")) pb.environment().put("DOCKER_HOST",env.get("DOCKER_HOST"));
        var process=pb.start();
        if (!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("Container API route probe timed out");}
        String error=new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        var result=new ObjectMapper().readTree(error);
        if (process.exitValue()!=2 || !"API_ERROR".equals(result.path("error").asText()) || result.path("status").asInt()!=401)
            throw new AssertionError("Container did not reach the scoped agent authentication boundary");
    }

    /** 이 Case의 DB Run 목록(agent_key, run_ref, status, outcome)에 러너의 model_finished 로그
     * (run_ref로 짝지은 비용·토큰·실패 코드)를 합쳐 Run별 결과 배열을 만들고, 전체 합계도 함께
     * 남긴다. 한 run_ref에 model_finished가 여러 번 남을 수 있다 -- lease 만료 뒤 재청구된 Run은
     * 이전 시도의 model_finished도 로그에 남기 때문에 -- 그래서 비용·토큰은 합산하고, 실패 코드는
     * 가장 마지막 것을 남긴다. */
    private static final List<String> METRICS = List.of("costUsd","inputTokens","outputTokens","cacheReadTokens","cacheWriteTokens","turns");
    private static final List<String> REQUIRED_USAGE = List.of("costUsd","inputTokens","outputTokens","cacheReadTokens","cacheWriteTokens");

    static boolean completeUsage(JsonNode event) {
        return event != null && !event.hasNonNull("failure")
                && event.hasNonNull("runtime") && event.hasNonNull("model") && event.hasNonNull("resolvedModel")
                && REQUIRED_USAGE.stream().allMatch(k -> event.hasNonNull(k) && event.get(k).isNumber());
    }

    static Map<String, Object> summarize(List<BusinessState.RunRecord> dbRuns, List<JsonNode> modelFinished) {
        Map<String,List<JsonNode>> byRef=new LinkedHashMap<>();
        for (var event:modelFinished) {
            String ref=event.path("runRef").asText(null);
            if (ref!=null) byRef.computeIfAbsent(ref,k -> new ArrayList<>()).add(event);
        }
        List<Map<String,Object>> runs=new ArrayList<>();
        List<String> failures=new ArrayList<>();
        boolean complete=!dbRuns.isEmpty();
        for (var row:dbRuns) {
            var events=byRef.getOrDefault(row.runRef(),List.of());
            var run=new LinkedHashMap<String,Object>();
            run.put("agentKey",row.agentKey());run.put("runRef",row.runRef());
            run.put("status",row.status());run.put("outcome",row.outcome());
            var last=events.isEmpty() ? null : events.getLast();
            for (String key:List.of("runtime","model","resolvedModel"))
                run.put(key,last==null ? null : last.path(key).asText(null));
            String failure=null;
            for (var event:events) if (event.hasNonNull("failure")) failure=event.path("failure").asText();
            run.put("failure",failure);
            if (failure!=null) failures.add(failure);
            boolean usageComplete=!events.isEmpty() && events.stream().allMatch(UatEvidence::completeUsage);
            run.put("usageReported",events.stream().anyMatch(e -> METRICS.stream().anyMatch(e::hasNonNull)));
            run.put("usageComplete",usageComplete);complete &= usageComplete;
            for (String key:METRICS) {
                boolean known=!events.isEmpty() && events.stream().allMatch(e -> e.hasNonNull(key) && e.get(key).isNumber());
                if (key.equals("costUsd")) {
                    var partial=events.stream().filter(e -> e.hasNonNull(key)).map(e -> e.get(key).decimalValue()).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                    run.put(key,known ? partial : null);
                    run.put("partialCostUsd",events.stream().anyMatch(e -> e.hasNonNull(key)) ? partial : null);
                } else run.put(key,known ? events.stream().mapToLong(e -> e.get(key).asLong()).sum() : null);
            }
            runs.add(run);
        }
        var summary=new LinkedHashMap<String,Object>();
        summary.put("runs",runs);summary.put("usageComplete",complete);summary.put("failures",failures);
        var knownCosts=runs.stream().filter(r -> r.get("partialCostUsd")!=null).map(r -> (java.math.BigDecimal)r.get("partialCostUsd")).toList();
        var partial=knownCosts.stream().reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        summary.put("partialCostUsd",knownCosts.isEmpty() ? null : partial);
        summary.put("costUsd",runs.isEmpty() || runs.stream().anyMatch(r -> r.get("costUsd")==null) ? null : partial);
        for (String key:METRICS) if (!key.equals("costUsd"))
            summary.put(key,runs.isEmpty() || runs.stream().anyMatch(r -> r.get(key)==null) ? null : runs.stream().mapToLong(r -> (Long)r.get(key)).sum());
        return summary;
    }

    static Path write(ObjectMapper mapper, Path dir, String tcId, Map<String, Object> record) throws java.io.IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(tcId + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        return file;
    }
}
