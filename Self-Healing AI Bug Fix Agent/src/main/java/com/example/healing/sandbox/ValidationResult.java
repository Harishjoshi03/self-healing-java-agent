package com.example.healing.sandbox;

public record ValidationResult(String command, Integer exitCode, long durationMillis,
                               String output, boolean timedOut, boolean sandboxed) {
    public boolean passed() {
        return sandboxed && !timedOut && Integer.valueOf(0).equals(exitCode);
    }
}