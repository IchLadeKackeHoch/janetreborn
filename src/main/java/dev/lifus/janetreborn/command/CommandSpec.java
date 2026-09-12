package dev.lifus.janetreborn.command;

import java.util.Objects;

public record CommandSpec(String name, String description) {
  public CommandSpec {
    if (Objects.requireNonNull(name, "name").isBlank()) {
      throw new IllegalArgumentException("Command name cannot be blank");
    }
    if (Objects.requireNonNull(description, "description").isBlank()) {
      throw new IllegalArgumentException("Command description cannot be blank");
    }
  }
}
