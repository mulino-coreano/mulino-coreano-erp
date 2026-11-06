package com.mulinocoreano.backend.planning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test-only loader. The seed is one atomic DO statement and joins the caller's transaction. */
public final class ReplenishmentDemoFixture {
    private ReplenishmentDemoFixture() {}

    public static void load(JdbcClient jdbc) throws IOException {
        Path path = Path.of("../database/seed/replenishment_demo.sql");
        if (!Files.exists(path)) path = Path.of("database/seed/replenishment_demo.sql");
        jdbc.sql(Files.readString(path)).update();
    }
}
