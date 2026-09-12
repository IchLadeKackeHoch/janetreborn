package dev.lifus.janetreborn.scripting;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ScriptLogBuffer {
  private final int capacity;
  private final ArrayDeque<ScriptLogEntry> entries = new ArrayDeque<>();
  private long sequence;

  public ScriptLogBuffer(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Log capacity must be positive");
    this.capacity = capacity;
  }

  public synchronized ScriptLogEntry add(String scriptId, ScriptSeverity severity, String message) {
    ScriptLogEntry entry =
        new ScriptLogEntry(Instant.now(), sequence++, severity, scriptId, sanitize(message));
    entries.addLast(entry);
    while (entries.size() > capacity) entries.removeFirst();
    return entry;
  }

  public synchronized List<ScriptLogEntry> snapshot() {
    return List.copyOf(entries);
  }

  public synchronized List<ScriptLogEntry> filtered(String scriptId, ScriptSeverity minimum) {
    List<ScriptLogEntry> result = new ArrayList<>();
    for (ScriptLogEntry entry : entries) {
      if ((scriptId == null || scriptId.isBlank() || entry.scriptId().equals(scriptId))
          && entry.severity().ordinal() >= minimum.ordinal()) result.add(entry);
    }
    return List.copyOf(result);
  }

  private static String sanitize(String message) {
    String value = message == null ? "null" : message;
    return value.length() <= 4096 ? value : value.substring(0, 4096) + "…";
  }
}
