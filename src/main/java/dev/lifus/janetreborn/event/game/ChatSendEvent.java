package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;
import java.util.Objects;

public final class ChatSendEvent extends Event {
  private final String message;

  public ChatSendEvent(String message) {
    this.message = Objects.requireNonNull(message, "message");
  }

  public String message() {
    return message;
  }
}
