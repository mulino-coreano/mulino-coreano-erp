package com.mulinocoreano.backend.scenario;

/** 시나리오는 flyway.clean()을 호출한다. 이름이 _scenario로 끝나는 loopback DB만 허용한다. */
public final class ScenarioGuard {
    private ScenarioGuard() {}

    public static void requireDisposable(String dbUrl) {
        if (dbUrl == null || !dbUrl.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_scenario")) {
            throw new IllegalStateException("Scenario tests wipe their schema: set DB_URL to a loopback database named *_scenario");
        }
    }
}
