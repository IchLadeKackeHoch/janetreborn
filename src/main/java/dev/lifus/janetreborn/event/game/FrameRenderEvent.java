package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;

public final class FrameRenderEvent extends Event {
  private final float tickDelta;

  public FrameRenderEvent(float tickDelta) {
    this.tickDelta = tickDelta;
  }

  public float getTickDelta() {
    return tickDelta;
  }
}
