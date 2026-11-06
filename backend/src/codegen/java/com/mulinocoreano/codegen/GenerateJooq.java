package com.mulinocoreano.codegen;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Build-only entry point. Never reads application database credentials or modifies an existing DB.
 */
public final class GenerateJooq {
    private GenerateJooq() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Migration and output directories are required");
        try (var postgres =
                new PostgreSQLContainer("postgres:18.6")
                        .withDatabaseName("mulino_codegen")
                        .withUsername("codegen")
                        .withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            Flyway.configure()
                    .loggers("slf4j")
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + args[0])
                    .load()
                    .migrate();
            GenerationTool.generate(
                    new Configuration()
                            .withLogging(Logging.WARN)
                            .withJdbc(
                                    new Jdbc()
                                            .withDriver("org.postgresql.Driver")
                                            .withUrl(postgres.getJdbcUrl())
                                            .withUser(postgres.getUsername())
                                            .withPassword(postgres.getPassword()))
                            .withGenerator(
                                    new Generator()
                                            .withDatabase(
                                                    new Database()
                                                            .withName(
                                                                    "org.jooq.meta.postgres.PostgresDatabase")
                                                            .withInputSchema("public")
                                                            .withExcludes("flyway_schema_history"))
                                            .withGenerate(
                                                    new Generate()
                                                            .withRecords(false)
                                                            .withPojos(false)
                                                            .withDaos(false)
                                                            .withRoutines(false)
                                                            .withImplicitJoinPathsToOne(false)
                                                            .withImplicitJoinPathsToMany(false)
                                                            .withImplicitJoinPathsManyToMany(false)
                                                            .withGeneratedAnnotation(false))
                                            .withTarget(
                                                    new Target()
                                                            .withPackageName(
                                                                    "com.mulinocoreano.backend.generated")
                                                            .withDirectory(args[1])
                                                            .withClean(true))));
        }
    }
}
