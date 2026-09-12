package dev.lifus.janetreborn.command;

import dev.lifus.janetreborn.api.ClientContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class Commands {
  private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]+");
  private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
  private final String prefix;
  private ClientContext client;

  public Commands() {
    this(".");
  }

  public Commands(String prefix) {
    if (Objects.requireNonNull(prefix, "prefix").isBlank()) {
      throw new IllegalArgumentException("Command prefix cannot be blank");
    }
    this.prefix = prefix;
  }

  public void bind(ClientContext client) {
    if (this.client != null && this.client != client) {
      throw new IllegalStateException("Command registry is already bound");
    }
    this.client = Objects.requireNonNull(client, "client");
  }

  public CommandHandle register(String name, String description, Command handler) {
    String normalized = normalize(name);
    CommandSpec descriptor = new CommandSpec(normalized, description.trim());
    RegisteredCommand command =
        new RegisteredCommand(descriptor, Objects.requireNonNull(handler, "handler"));
    if (commands.putIfAbsent(normalized, command) != null) {
      throw new IllegalArgumentException("Command already registered: " + normalized);
    }
    return command;
  }

  public CommandResult execute(String input) {
    if (client == null) throw new IllegalStateException("Command registry is not bound");
    List<String> parts;
    try {
      parts = tokenize(Objects.requireNonNull(input, "input").trim());
    } catch (IllegalArgumentException exception) {
      return CommandResult.error(exception.getMessage());
    }
    if (parts.isEmpty()) return CommandResult.error("Enter a command after " + prefix);
    String name;
    try {
      name = normalize(parts.getFirst());
    } catch (IllegalArgumentException exception) {
      return CommandResult.error(exception.getMessage());
    }
    RegisteredCommand command = commands.get(name);
    if (command == null) return CommandResult.error("Unknown command: " + name);
    try {
      CommandResult result =
          command.handler.execute(
              new CommandCall(client, name, parts.subList(1, parts.size()), input));
      return Objects.requireNonNull(result, "Command returned no result");
    } catch (IllegalArgumentException exception) {
      return CommandResult.error(exception.getMessage());
    } catch (Exception exception) {
      return CommandResult.error("Command failed: " + exception.getClass().getSimpleName());
    }
  }

  public boolean dispatchChat(String message) {
    Objects.requireNonNull(message, "message");
    if (!message.startsWith(prefix)) return false;
    CommandResult result = execute(message.substring(prefix.length()));
    client
        .minecraft()
        .gui
        .chatListener()
        .handleSystemMessage(
            Component.literal(result.message())
                .withStyle(result.successful() ? ChatFormatting.GREEN : ChatFormatting.RED),
            false);
    return true;
  }

  public Collection<CommandSpec> commands() {
    return commands.values().stream().map(command -> command.descriptor).toList();
  }

  public String prefix() {
    return prefix;
  }

  private static String normalize(String name) {
    String normalized = Objects.requireNonNull(name, "name").trim().toLowerCase(Locale.ROOT);
    if (!VALID_NAME.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Invalid command name: " + name);
    }
    return normalized;
  }

  private static List<String> tokenize(String input) {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    boolean quoted = false;
    boolean escaping = false;
    for (int index = 0; index < input.length(); index++) {
      char current = input.charAt(index);
      if (escaping) {
        token.append(current);
        escaping = false;
      } else if (current == '\\') {
        escaping = true;
      } else if (current == '"') {
        quoted = !quoted;
      } else if (Character.isWhitespace(current) && !quoted) {
        if (!token.isEmpty()) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else {
        token.append(current);
      }
    }
    if (escaping || quoted) throw new IllegalArgumentException("Unclosed command quote or escape");
    if (!token.isEmpty()) tokens.add(token.toString());
    return tokens;
  }

  private final class RegisteredCommand implements CommandHandle {
    private final CommandSpec descriptor;
    private final Command handler;
    private boolean registered = true;

    private RegisteredCommand(CommandSpec descriptor, Command handler) {
      this.descriptor = descriptor;
      this.handler = handler;
    }

    @Override
    public CommandSpec descriptor() {
      return descriptor;
    }

    @Override
    public boolean isRegistered() {
      return registered;
    }

    @Override
    public void close() {
      if (!registered) return;
      registered = false;
      commands.remove(descriptor.name(), this);
    }
  }
}
