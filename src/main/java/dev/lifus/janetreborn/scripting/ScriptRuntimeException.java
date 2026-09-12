package dev.lifus.janetreborn.scripting;

import java.nio.file.Path;

public final class ScriptRuntimeException extends RuntimeException {
  private final Path file;
  private final int line;

  public ScriptRuntimeException(Path file, int line, String message, Throwable cause) {
    super(message, cause);
    this.file = file;
    this.line = line;
  }

  public Path file() {
    return file;
  }

  public int line() {
    return line;
  }
}
