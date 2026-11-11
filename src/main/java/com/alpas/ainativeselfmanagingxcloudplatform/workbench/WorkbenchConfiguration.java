package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Configuration
public class WorkbenchConfiguration {
    @Bean
    StateStore stateStore(
            JsonMapper json,
            @Value("${platform.data-dir:.workbench}") String directory,
            @Value("${platform.database-url:}") String databaseUrl,
            @Value("${platform.coordinator.mode:single}") String mode,
            @Value("${platform.coordinator.lease-seconds:30}") int leaseSeconds,
            @Value("${platform.coordinator.acquire-timeout-seconds:60}") int acquireTimeoutSeconds,
            @Value("${platform.runtime:sandbox}") String runtime,
            @Value("${platform.execution.mode:local}") String execution,
            @Value("${platform.github.publish-repository:}") String publication) {
        validateCoordination(mode, runtime, execution, publication);
        return new StateStore(json, directory, databaseUrl, mode, leaseSeconds, acquireTimeoutSeconds);
    }

    static void validateCoordination(String mode, String runtime, String execution, String publication) {
        if (mode.equals("lease") && (!runtime.equals("sandbox") || !execution.equals("remote") || !publication.isBlank()))
            throw new IllegalArgumentException("Lease coordination supports the durable simulator and remote tests only;"
                    + " external runtime writes and GitHub publication require single mode");
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
