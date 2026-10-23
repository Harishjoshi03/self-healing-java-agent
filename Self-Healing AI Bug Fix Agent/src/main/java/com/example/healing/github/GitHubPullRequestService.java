package com.example.healing.github;

import com.example.healing.incident.application.SecretRedactor;
import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.patch.IsolatedWorkspace;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.github.enabled", havingValue = "true")
public class GitHubPullRequestService implements PullRequestService {

    private static final Pattern REPOSITORY_ID = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private final String configuredRepository;
    private final String baseBranch;
    private final String token;
    private final String apiBaseUrl;
    private final SecretRedactor redactor;
    private final HttpClient httpClient;

    public GitHubPullRequestService(@Value("${agent.repository.id:}") String configuredRepository,
                                    @Value("${agent.repository.base-branch:main}") String baseBranch,
                                    @Value("${agent.github.token:}") String token,
                                    @Value("${agent.github.api-url:https://api.github.com}") String apiBaseUrl,
                                    SecretRedactor redactor) {
        if (!REPOSITORY_ID.matcher(configuredRepository).matches()) {
            throw new IllegalStateException("Configure AGENT_REPOSITORY_ID as owner/repository before enabling PRs");
        }
        if (token.isBlank()) {
            throw new IllegalStateException("Set GITHUB_TOKEN before enabling PR creation");
        }
        this.configuredRepository = configuredRepository;
        this.baseBranch = baseBranch;
        this.token = token;
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.redactor = redactor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public PullRequestResult create(IncidentEntity incident, IsolatedWorkspace workspace, String summary,
                                    List<String> changedFiles, String testReport) {
        if (!configuredRepository.equals(incident.getRepository())) {
            throw new IllegalStateException("Incident repository does not match the configured GitHub repository");
        }
        try {
            configureRemote(workspace);
            commitAndPush(workspace, incident, changedFiles, summary);
            String body = buildBody(incident, summary, changedFiles, testReport);
            String response = createPullRequest(body, workspace.branch(), incident.getErrorMessage());
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            return new PullRequestResult(tree.path("html_url").asText(), tree.path("number").asInt());
        } catch (IOException exception) {
            throw new IllegalStateException("GitHub pull request operation failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub pull request operation was interrupted", exception);
        }
    }

    private void configureRemote(IsolatedWorkspace workspace) throws IOException, InterruptedException {
        runGit(workspace.root(), null, "remote", "set-url", "origin",
            "https://github.com/" + configuredRepository + ".git");
    }

    private void commitAndPush(IsolatedWorkspace workspace, IncidentEntity incident, List<String> changedFiles,
                               String summary) throws IOException, InterruptedException {
        List<String> addCommand = new ArrayList<>(List.of("add", "--"));
        addCommand.addAll(changedFiles);
        runGit(workspace.root(), null, addCommand.toArray(String[]::new), false);
        runGit(workspace.root(), null, new String[] {"config", "user.name", "Self-Healing Agent"}, false);
        runGit(workspace.root(), null,
            new String[] {"config", "user.email", "self-healing-agent@users.noreply.github.com"}, false);
        runGit(workspace.root(), null, "commit", "--no-gpg-sign", "-m",
            redactor.redact("fix: " + summary).substring(0, Math.min(180, ("fix: " + summary).length())));
        String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        runGit(workspace.root(), basic,
            new String[] {"push", "--porcelain", "origin", "HEAD:refs/heads/" + workspace.branch()}, true);
    }

    private String createPullRequest(String body, String head, String title)
            throws IOException, InterruptedException {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("title", redactor.redact("[Incident] " + title).substring(0,
                                Math.min(200, ("[Incident] " + title).length())),
                        "head", head, "base", baseBranch, "body", body));
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/repos/" + configuredRepository + "/pulls"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String buildBody(IncidentEntity incident, String summary, List<String> changedFiles, String testReport) {
        return "## Incident\n" + incident.getId() + "\n\n## Diagnosis and patch\n" + redactor.redact(summary)
                + "\n\n## Changed files\n" + String.join("\n", changedFiles.stream().map(path -> "- " + path).toList())
                + "\n\n## Validation\n```text\n" + redactor.redact(testReport) + "\n```"
                + "\n\nReview and merge require a human. This agent does not merge or deploy.";
    }

    private void runGit(java.nio.file.Path root, String basicAuth, String... arguments)
            throws IOException, InterruptedException {
        runGit(root, basicAuth, arguments, false);
    }

    private void runGit(java.nio.file.Path root, String basicAuth, String[] arguments, boolean authenticated)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        if (authenticated) {
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "http.extraheader");
            builder.environment().put("GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basicAuth);
        }
        Process process = builder.start();
        if (!process.waitFor(Duration.ofSeconds(60).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("GitHub git operation timed out");
        }
        if (process.exitValue() != 0) {
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            throw new IOException("GitHub git operation failed with exit code " + process.exitValue());
        }
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
    }
}