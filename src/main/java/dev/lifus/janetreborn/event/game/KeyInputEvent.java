package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;

public final class KeyInputEvent extends Event {
  private final int key;
  private final int action;

  public KeyInputEvent(int key, int action) {
    this.key = key;
    this.action = action;
  }

  public int key() {
    return key;
  }

  public int action() {
    return action;
  }
}
