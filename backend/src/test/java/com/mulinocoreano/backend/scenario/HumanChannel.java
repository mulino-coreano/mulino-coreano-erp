package com.mulinocoreano.backend.scenario;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사람은 실제 사용자와 같은 경로(역할별 stdio MCP)로만 행동한다. */
public final class HumanChannel {
    public record ToolResult(boolean isError, JsonNode content) {}

    private static final int STDERR_TAIL_CHARS = 2000;

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private final ObjectMapper mapper;
    private final String apiBase;

    public HumanChannel(ObjectMapper mapper, String apiBase) { this.mapper = mapper; this.apiBase = apiBase; }

    public ToolResult call(String role, String tool, Map<String, Object> args) {
        StringBuilder stderr = new StringBuilder();
        try {
            var pb = new ProcessBuilder("node", ROOT.resolve("mcp-server/scripts/scenario/human.mjs").toString(),
                    apiBase, role, tool, mapper.writeValueAsString(args)).redirectErrorStream(false);
            pb.environment().keySet().retainAll(java.util.Set.of("PATH", "HOME"));
            Process p = pb.start();
            // human.mjs's StdioClientTransport inherits stderr from the spawned mcp-server child
            // (its "mulino-erp stdio MCP server running" log and any diagnostics). Left unread,
            // that pipe can fill and stall the whole call until the 30s kill below. Drain it on a
            // background thread so it never blocks, and keep a bounded tail for failure messages.
            Thread stderrReader = new Thread(() -> {
                try (var reader = p.errorReader()) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        synchronized (stderr) {
                            stderr.append(buf, 0, n);
                            int excess = stderr.length() - STDERR_TAIL_CHARS;
                            if (excess > 0) stderr.delete(0, excess);
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // process ended or pipe closed; nothing left to drain
                }
            }, "human-channel-stderr-" + role);
            stderrReader.setDaemon(true);
            stderrReader.start();

            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                // human.mjs itself is only the direct child; it spawns the per-role mcp-server as
                // its own child process (a grandchild of this JVM). Killing just `p` leaves that
                // grandchild running, so kill the whole tree before destroying p.
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new AssertionError(role + " " + tool + " timed out" + stderrTail(stderr));
            }
            stderrReader.join(2000);
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.exitValue() != 0 || out.isEmpty()) throw new AssertionError(role + " " + tool + " failed to run" + stderrTail(stderr));
            JsonNode line = mapper.readTree(out.substring(out.lastIndexOf('\n') + 1));
            return new ToolResult(line.path("isError").asBoolean(), line.path("content"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static String stderrTail(StringBuilder stderr) {
        String captured;
        synchronized (stderr) { captured = stderr.toString(); }
        return captured.isBlank() ? "" : "\nstderr (tail):\n" + captured;
    }
}
