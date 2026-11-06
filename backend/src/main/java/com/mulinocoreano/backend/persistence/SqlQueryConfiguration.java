package com.mulinocoreano.backend.persistence;

import org.jooq.conf.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlQueryConfiguration {
    @Bean
    Settings jooqSettings() {
        // The connection's search_path selects the schema. This also keeps each
        // disposable integration-test schema isolated from the generated public schema.
        return new Settings().withRenderSchema(false);
    }
}
