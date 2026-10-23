package com.example.healing.patch;

public record PatchPlan(String summary, String unifiedDiff, boolean requiresHumanReview) {
}