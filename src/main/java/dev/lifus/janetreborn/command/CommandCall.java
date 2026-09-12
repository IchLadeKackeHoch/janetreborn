package dev.lifus.janetreborn.command;

import dev.lifus.janetreborn.api.ClientContext;
import java.util.List;
import java.util.Objects;

public record CommandCall(
    ClientContext client, String command, List<String> arguments, String input) {
  public CommandCall {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(command, "command");
    arguments = List.copyOf(arguments);
    Objects.requireNonNull(input, "input");
  }

  public String requireArgument(int index, String label) {
    if (index < arguments.size()) return arguments.get(index);
    throw new IllegalArgumentException("Missing " + label);
  }
}
