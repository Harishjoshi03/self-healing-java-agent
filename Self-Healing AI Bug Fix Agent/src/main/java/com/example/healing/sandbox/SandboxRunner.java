package com.example.healing.sandbox;

import com.example.healing.patch.IsolatedWorkspace;

public interface SandboxRunner {
    ValidationResult validate(IsolatedWorkspace workspace);
}