package com.mulinocoreano.backend.scenario;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 실행기 프로세스 하나를 시나리오 동안 돌리고, 업무 상태 도달을 기다리며, 끝나면 반드시 종료한다. */
public final class AgentDriver {
    private final ObjectMapper mapper;
    private final List<String> command;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private Map<String, String> env = Map.of();
    private Process process;

    public AgentDriver(ObjectMapper mapper, List<String> command) { this.mapper = mapper; this.command = command; }

    public void start(Map<String, String> env) {
        this.env = env;
        try {
            var pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().keySet().retainAll(java.util.Set.of("PATH", "HOME"));
            pb.environment().putAll(env);
            process = pb.start();
            Thread reader = new Thread(() -> {
                try (var in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    for (String l; (l = in.readLine()) != null; ) lines.add(l);
                } catch (java.io.IOException ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
        } catch (java.io.IOException e) { throw new AssertionError("runner failed to start", e); }
    }

    public void awaitState(String expected, BooleanSupplier reached, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (reached.getAsBoolean()) return;
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        List<String> codes = modelFinished().stream().map(e -> e.path("failure").asText("")).filter(s -> !s.isEmpty()).toList();
        throw new AssertionError("Agent did not reach: " + expected + " within " + timeout + "; model failures: " + codes);
    }

    public List<JsonNode> modelFinished() {
        List<JsonNode> out = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                try { JsonNode n = mapper.readTree(l); if ("model_finished".equals(n.path("event").asText())) out.add(n); }
                catch (RuntimeException notJson) { }
            }
        }
        return out;
    }

    public void restart() { stop(); start(env); }

    public boolean isAlive() { return process != null && process.isAlive(); }

    /** Test hook: the runner's own OS pid, used to look up its live children directly (no stdout parsing). */
    long pid() { return process.pid(); }

    public void stop() {
        if (process == null) return;
        // Snapshot descendants (the runner's own children -- the scripted agent now, `docker run` in
        // Task 5's live mode) while the process is still alive. Once it exits -- cooperatively below,
        // or forcibly -- the OS no longer reports its former children through this handle, so this must
        // happen before the first signal, not after the cooperative wait times out.
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
