package com.example.healing.diagnosis;

import java.util.List;

public record DiagnosisReport(String summary, String likelyRootCause, double confidence,
                              List<String> evidenceReferences, List<String> suspectedFiles,
                              String proposedChange, List<String> testsToRun,
                              List<String> missingInformation, String riskLevel,
                              boolean requiresHumanReview) {
    public DiagnosisReport {
        evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        suspectedFiles = suspectedFiles == null ? List.of() : List.copyOf(suspectedFiles);
        testsToRun = testsToRun == null ? List.of() : List.copyOf(testsToRun);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
    }
}