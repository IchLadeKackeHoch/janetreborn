package dev.lifus.janetreborn.command;

import java.util.Objects;

public record CommandResult(boolean successful, String message) {
  public CommandResult {
    Objects.requireNonNull(message, "message");
  }

  public static CommandResult success(String message) {
    return new CommandResult(true, message);
  }

  public static CommandResult error(String message) {
    return new CommandResult(false, message);
  }
}
