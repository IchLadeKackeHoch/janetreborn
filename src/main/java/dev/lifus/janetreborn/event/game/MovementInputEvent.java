package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

public final class MovementInputEvent extends Event {
  private final LocalPlayer player;
  private Input input;
  private Object owner;

  public MovementInputEvent(LocalPlayer player, Input input) {
    this.player = Objects.requireNonNull(player, "player");
    this.input = Objects.requireNonNull(input, "input");
  }

  public LocalPlayer player() {
    return player;
  }

  public Input input() {
    return input;
  }

  public boolean isClaimed() {
    return owner != null;
  }

  public boolean claim(Object owner, Input input) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(input, "input");
    if (this.owner != null && this.owner != owner) return false;
    this.owner = owner;
    this.input = input;
    return true;
  }
}
