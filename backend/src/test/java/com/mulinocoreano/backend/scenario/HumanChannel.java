package com.mulinocoreano.backend.scenario;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사람은 실제 사용자와 같은 경로(역할별 stdio MCP)로만 행동한다. */
public final class HumanChannel {
    public record ToolResult(boolean isError, JsonNode content) {}

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private final ObjectMapper mapper;
    private final String apiBase;

    public HumanChannel(ObjectMapper mapper, String apiBase) { this.mapper = mapper; this.apiBase = apiBase; }

    public ToolResult call(String role, String tool, Map<String, Object> args) {
        try {
            var pb = new ProcessBuilder("node", ROOT.resolve("mcp-server/scripts/scenario/human.mjs").toString(),
                    apiBase, role, tool, mapper.writeValueAsString(args)).redirectErrorStream(false);
            pb.environment().keySet().retainAll(java.util.Set.of("PATH", "HOME"));
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError(role + " " + tool + " timed out"); }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0 || out.isEmpty()) throw new AssertionError(role + " " + tool + " failed to run");
            JsonNode line = mapper.readTree(out.substring(out.lastIndexOf('\n') + 1));
            return new ToolResult(line.path("isError").asBoolean(), line.path("content"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
