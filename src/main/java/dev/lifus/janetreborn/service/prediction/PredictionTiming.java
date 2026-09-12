package dev.lifus.janetreborn.service.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

public final class PredictionTiming {
  private static final int MAX_EXECUTION_HORIZON = 6;
  private static final int MAX_RESPONSE_WINDOW = 8;

  private PredictionTiming() {}

  public static int executionHorizon(Minecraft minecraft, Player target) {
    int outboundTicks = (int) Math.ceil(latency(minecraft, minecraft.player) / 100.0);
    return Math.max(1, Math.min(MAX_EXECUTION_HORIZON, 1 + outboundTicks));
  }

  public static int responseWindow(Minecraft minecraft, Player target, int minimumTicks) {
    int latencyTicks =
        (int)
            Math.ceil((latency(minecraft, minecraft.player) + latency(minecraft, target)) / 100.0);
    return Math.max(minimumTicks, Math.min(MAX_RESPONSE_WINDOW, minimumTicks + latencyTicks));
  }

  private static int latency(Minecraft minecraft, Player player) {
    if (player == null || minecraft.getConnection() == null) return 0;
    PlayerInfo info = minecraft.getConnection().getPlayerInfo(player.getUUID());
    return info == null ? 0 : Math.max(0, Math.min(1000, info.getLatency()));
  }
}
