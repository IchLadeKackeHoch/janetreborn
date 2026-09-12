package dev.lifus.janetreborn.scripting;

public record ScriptLimits(
    long instructionsPerCallback,
    long callbackNanos,
    int recursionDepth,
    int repeatedOverruns,
    int scheduledTasks,
    int queryResults) {
  public static final ScriptLimits DEFAULT = new ScriptLimits(200_000, 25_000_000, 16, 3, 256, 256);

  public ScriptLimits {
    if (instructionsPerCallback < 1
        || callbackNanos < 1
        || recursionDepth < 1
        || repeatedOverruns < 1
        || scheduledTasks < 1
        || queryResults < 1) throw new IllegalArgumentException("Script limits must be positive");
  }
}
