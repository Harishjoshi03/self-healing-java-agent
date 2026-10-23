package com.example.healing.repository;

import java.util.List;

public record RepositoryContext(String repository, String branch, String commit,
                               List<RepositoryEvidence> evidence, List<String> limitations) {
}