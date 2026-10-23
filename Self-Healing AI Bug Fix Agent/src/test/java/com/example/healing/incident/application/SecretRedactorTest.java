package com.example.healing.incident.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsAssignmentsBearerTokensAndAwsKeys() {
        String sanitized = redactor.redact("password=hunter2 Authorization: Bearer abc.def "
            + "AKIA1234567890ABCDEF sk-123456789012345678901234 github_pat_12345678901234567890");

        assertThat(sanitized).contains("password=[REDACTED]")
            .contains("Authorization: [REDACTED]")
                .contains("[REDACTED_AWS_KEY]")
            .contains("[REDACTED_TOKEN]")
            .doesNotContain("hunter2", "abc.def", "AKIA1234567890ABCDEF",
                "sk-123456789012345678901234", "github_pat_12345678901234567890");
    }
}