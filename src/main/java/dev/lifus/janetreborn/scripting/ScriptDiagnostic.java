package dev.lifus.janetreborn.scripting;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;

public record ScriptDiagnostic(
    Instant timestamp,
    ScriptSeverity severity,
    String scriptId,
    Path file,
    int line,
    int column,
    String message,
    String stackTrace) {
  public static ScriptDiagnostic error(
      String scriptId, Path file, int line, int column, String message, Throwable cause) {
    String trace = "";
    if (cause != null) {
      StringWriter writer = new StringWriter();
      cause.printStackTrace(new PrintWriter(writer));
      trace = writer.toString();
    }
    return new ScriptDiagnostic(
        Instant.now(), ScriptSeverity.ERROR, scriptId, file, line, column, message, trace);
  }
}
