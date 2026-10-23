package com.example.healing.api;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
public class OpenApiConfiguration {

    @Bean
    OpenAPI selfHealingAgentApi() {
        return new OpenAPI().info(new Info()
                .title("Self-Healing Java Bug Fix Agent API")
                .version("v1")
            .description("Authenticated incident submission, workflow status, and evidence-backed results."))
            .servers(List.of(new Server().url("/").description("Current host")));
    }
}