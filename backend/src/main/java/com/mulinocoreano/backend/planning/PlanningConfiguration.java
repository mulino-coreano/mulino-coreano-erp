package com.mulinocoreano.backend.planning;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class PlanningConfiguration {
    @Bean("planningClock")
    Clock planningClock() { return Clock.system(ZoneId.of("Asia/Seoul")); }
}
