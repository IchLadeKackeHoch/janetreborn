package dev.lifus.janetreborn.service.player;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import java.util.ArrayDeque;
import java.util.Objects;
import net.minecraft.client.Minecraft;

public final class Teleports {
  private static final int PEARL_FLIGHT_WINDOW_TICKS = 300;
  private final EventBus eventBus;
  private final Minecraft minecraft;
  private final ArrayDeque<Long> pendingPearls = new ArrayDeque<>();
  private long tick;

  public Teleports(EventBus eventBus, Minecraft minecraft) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public void tick() {
    tick++;
    while (!pendingPearls.isEmpty() && pendingPearls.peekFirst() < tick)
      pendingPearls.removeFirst();
  }

  public void noteEnderPearlUse() {
    pendingPearls.addLast(tick + PEARL_FLIGHT_WINDOW_TICKS);
  }

  public void handleIncomingTeleport() {
    if (!pendingPearls.isEmpty()) {
      pendingPearls.removeFirst();
      return;
    }
    if (minecraft.player == null || minecraft.player.tickCount < 20) return;
    eventBus.publish(new ServerTeleportEvent());
  }

  static boolean isServerTeleport(int pendingPearls) {
    return pendingPearls == 0;
  }
}
