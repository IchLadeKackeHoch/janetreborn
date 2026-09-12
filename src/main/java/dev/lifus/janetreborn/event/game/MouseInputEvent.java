package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;

public final class MouseInputEvent extends Event {
  private final String kind;
  private final int button;
  private final int action;
  private final double horizontal;
  private final double vertical;

  public MouseInputEvent(String kind, int button, int action, double horizontal, double vertical) {
    this.kind = kind;
    this.button = button;
    this.action = action;
    this.horizontal = horizontal;
    this.vertical = vertical;
  }

  public String kind() {
    return kind;
  }

  public int button() {
    return button;
  }

  public int action() {
    return action;
  }

  public double horizontal() {
    return horizontal;
  }

  public double vertical() {
    return vertical;
  }
}
