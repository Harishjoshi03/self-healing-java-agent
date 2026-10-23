package com.example.healing.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record IncidentRequest(
        @Schema(description = "Name of the affected service", example = "orders")
        @NotBlank @Size(max = 120) String serviceName,
        @Schema(description = "Server-allowlisted repository identifier", example = "owner/orders")
        @NotBlank @Size(max = 180) @Pattern(regexp = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") String repository,
        @Schema(description = "Allowlisted branch to inspect", example = "main")
        @NotBlank @Size(max = 120) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*") String branch,
        @Schema(description = "Short error summary; secrets are redacted", example = "Unexpected result in Calculator.add")
        @NotBlank @Size(max = 4000) String errorMessage,
        @Schema(description = "Stack trace or relevant log excerpt, at most 32,000 characters")
        @NotBlank @Size(max = 32000) String stackTrace,
        @Schema(description = "Optional deployment environment", example = "test")
        @Size(max = 80) String environment,
        @Schema(description = "Optional trace or request correlation ID", example = "trace-001")
        @Size(max = 120) String correlationId
) {
}