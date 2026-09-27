package com.mulinocoreano.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 목표 4 보조: 에이전트가 기대 상태에 못 가면 멈추지 않고 이유와 함께 실패하며, 실행기는 반드시 정리된다. */
class AgentDriverTest {
    @Test
    void unreachedStateFailsWithinTheTimeoutAndTheChildIsKilled() {
        var driver = new AgentDriver(new ObjectMapper(),
                List.of("node", "-e", "console.log(JSON.stringify({event:'model_finished',failure:'MODEL_OUTPUT_TOO_LARGE'})); setInterval(()=>{},1000)"));
        driver.start(Map.of());
        assertThatThrownBy(() -> driver.awaitState("구매 제안 승인 대기", () -> false, Duration.ofMillis(800)))
                .hasMessageContaining("구매 제안 승인 대기").hasMessageContaining("MODEL_OUTPUT_TOO_LARGE");
        driver.stop();
        assertThat(driver.isAlive()).isFalse();
    }

    /** 목표 4 보조: 실행기 프로세스가 이미 죽었으면 타임아웃을 다 기다리지 않고 바로 실패해야
     * 한다 -- 그래야 CI가 죽은 러너 뒤에서 몇 분씩 헛되이 대기하지 않는다. */
    @Test
    void diesEarlyFailsWellBeforeTheTimeoutNamingTheExpectedState() {
        var driver = new AgentDriver(new ObjectMapper(), List.of("node", "-e", "console.log('runner exiting now')"));
        driver.start(Map.of());

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> driver.awaitState("구매 제안 승인 대기", () -> false, Duration.ofMinutes(15)))
                .hasMessageContaining("구매 제안 승인 대기");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        // Nowhere near the 15-minute timeout: the process death must be caught on the next poll,
        // not after waiting the whole duration out.
        assertThat(elapsedMs).isLessThan(5000);
        driver.stop();
    }

    /**
     * 수정 1차: stop()이 실행기(직접 자식) 하나만 강제 종료했다. 실행기가 Run마다 자기 자식을 또
     * 띄우고(지금은 scripted agent, Task 5 live 모드에서는 docker run) SIGTERM을 무시하면 그
     * 손자 프로세스가 고아로 남았다. 손자가 stop() 이후 살아남지 않음을 증명한다.
     */
    @Test
    void stopKillsGrandchildProcessesEvenWhenTheRunnerIgnoresSigterm() throws InterruptedException {
        String script = "process.on('SIGTERM', () => {}); "
                + "const cp = require('child_process').spawn(process.execPath, "
                + "['-e', 'process.on(\"SIGTERM\",()=>{});setInterval(()=>{},1000)'], "
                + "{stdio:['ignore','ignore','ignore']}); "
                + "console.log(String(cp.pid)); "
                + "setInterval(()=>{},1000);";
        var driver = new AgentDriver(new ObjectMapper(), List.of("node", "-e", script));
        driver.start(Map.of());
        ProcessHandle grandchild = awaitOnlyChild(driver.pid());
        assertThat(grandchild.isAlive()).isTrue();

        driver.stop();

        assertThat(driver.isAlive()).isFalse();
        assertThat(grandchild.isAlive()).isFalse();
    }

    /**
     * kill()이 stop()과 실제로 다르게 동작함을 증명한다: stop()은 이 스크립트처럼 SIGTERM을
     * 무시하는 프로세스를 만나면 10초 협조 대기 후에야 강제 종료로 넘어간다. kill()은 SIGTERM을
     * 아예 보내지 않고 곧바로 강제 종료해야 하므로, SIGTERM을 무시하는 프로세스라도 stop()의
     * 10초 대기 없이 곧바로(수 초 안에) 죽어야 한다.
     */
    @Test
    void killDestroysTheWholeProcessTreeImmediatelyWithoutSigterm() throws InterruptedException {
        String script = "process.on('SIGTERM', () => {}); "
                + "const cp = require('child_process').spawn(process.execPath, "
                + "['-e', 'process.on(\"SIGTERM\",()=>{});setInterval(()=>{},1000)'], "
                + "{stdio:['ignore','ignore','ignore']}); "
                + "console.log(String(cp.pid)); "
                + "setInterval(()=>{},1000);";
        var driver = new AgentDriver(new ObjectMapper(), List.of("node", "-e", script));
        driver.start(Map.of());
        ProcessHandle grandchild = awaitOnlyChild(driver.pid());
        assertThat(grandchild.isAlive()).isTrue();

        long startNanos = System.nanoTime();
        driver.kill();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(driver.isAlive()).isFalse();
        assertThat(grandchild.isAlive()).isFalse();
        // stop()'s cooperative wait for this same script is 10s before it escalates; kill() must
        // never wait that long because it never sends SIGTERM in the first place.
        assertThat(elapsedMs).isLessThan(5000);
    }

    private static ProcessHandle awaitOnlyChild(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> child = ProcessHandle.of(pid).flatMap(p -> p.children().findFirst());
            if (child.isPresent()) return child.get();
            Thread.sleep(50);
        }
        throw new AssertionError("runner never spawned its grandchild within 5s");
    }
}
