package com.example.healing.incident.application;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException() {
        super("Incident not found");
    }
}