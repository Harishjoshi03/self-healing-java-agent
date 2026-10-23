package com.example.healing.patch;

public class PatchRejectedException extends RuntimeException {
    public PatchRejectedException(String message) {
        super(message);
    }
}