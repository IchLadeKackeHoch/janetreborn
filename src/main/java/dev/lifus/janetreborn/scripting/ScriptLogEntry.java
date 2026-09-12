package dev.lifus.janetreborn.scripting;

import java.time.Instant;

public record ScriptLogEntry(
    Instant timestamp, long sequence, ScriptSeverity severity, String scriptId, String message) {}
