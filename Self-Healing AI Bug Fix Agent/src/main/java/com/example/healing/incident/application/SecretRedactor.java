package com.example.healing.incident.application;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SecretRedactor {

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[_-]?key|authorization|client[_-]?secret)"
                + "(\\s*[:=]\\s*)(?:Bearer\\s+)?(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s,;]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
        private static final Pattern PROVIDER_TOKEN = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b");

    public String redact(String input) {
        String output = SECRET_ASSIGNMENT.matcher(input).replaceAll("$1$2[REDACTED]");
        output = BEARER_TOKEN.matcher(output).replaceAll("Bearer [REDACTED]");
        output = AWS_ACCESS_KEY.matcher(output).replaceAll("[REDACTED_AWS_KEY]");
        return PROVIDER_TOKEN.matcher(output).replaceAll("[REDACTED_TOKEN]");
    }
}