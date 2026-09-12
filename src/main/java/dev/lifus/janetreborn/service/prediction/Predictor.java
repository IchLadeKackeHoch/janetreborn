package dev.lifus.janetreborn.service.prediction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Predictor {
  private final Minecraft minecraft;
  private final Map<UUID, ShadowPlayer> shadows = new HashMap<>();
  private Map<UUID, PredictionPath> paths = Map.of();
  private PredictionConfig configuration = PredictionConfig.DEFAULT;
  private ClientLevel level;
  private int generation;

  public Predictor(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public void tick() {
    ClientLevel currentLevel = minecraft.level;
    if (currentLevel == null) {
      clear();
      return;
    }
    if (level != currentLevel) {
      clear();
      level = currentLevel;
    }

    generation++;
    Set<UUID> present = new HashSet<>();
    Map<UUID, PredictionPath> nextPaths = new HashMap<>();
    for (Player player : currentLevel.players()) {
      if (player.isRemoved()) continue;
      UUID playerId = player.getUUID();
      present.add(playerId);
      PredictionPath path = simulate(player, configuration);
      nextPaths.put(playerId, path);
    }
    shadows.keySet().removeIf(playerId -> !present.contains(playerId));
    paths = Map.copyOf(nextPaths);
  }

  public void setConfiguration(PredictionConfig configuration) {
    this.configuration = Objects.requireNonNull(configuration, "configuration");
  }

  public PredictionConfig getConfiguration() {
    return configuration;
  }

  public Map<UUID, PredictionPath> getPaths() {
    return paths;
  }

  public Prediction predict(Player player, int horizonTicks) {
    Objects.requireNonNull(player, "player");
    int horizon = Math.max(0, Math.min(PredictionConfig.MAX_DURATION_TICKS, horizonTicks));
    if (player.isRemoved() || !(player.level() instanceof ClientLevel currentLevel)) {
      return fallback(player, horizon);
    }
    ensureLevel(currentLevel);
    PredictionConfig request = new PredictionConfig(horizon, 1, horizon + 1);
    PredictionPath path = simulate(player, request);
    Vec3 position = path.endpoint();
    AABB bounds = player.getBoundingBox().move(position.subtract(player.position()));
    return new Prediction(position, position, bounds, 1.0f, true, horizon);
  }

  public void remove(Player player) {
    if (player == null) return;
    UUID playerId = player.getUUID();
    shadows.remove(playerId);
    if (paths.containsKey(playerId)) {
      Map<UUID, PredictionPath> retained = new HashMap<>(paths);
      retained.remove(playerId);
      paths = Map.copyOf(retained);
    }
  }

  public void clear() {
    shadows.clear();
    paths = Map.of();
    level = null;
  }

  private void ensureLevel(ClientLevel currentLevel) {
    if (level == currentLevel) return;
    clear();
    level = currentLevel;
  }

  private PredictionPath simulate(Player player, PredictionConfig request) {
    ShadowPlayer shadow =
        shadows.computeIfAbsent(
            player.getUUID(),
            ignored -> new ShadowPlayer((ClientLevel) player.level(), player.getGameProfile()));
    shadow.mirror(player);

    List<Vec3> samples = new ArrayList<>();
    samples.add(shadow.position());
    int sampleInterval =
        Math.max(
            request.simulationStepTicks(),
            (int) Math.ceil((double) request.durationTicks() / (request.trailLength() - 1)));
    for (int elapsed = 1; elapsed <= request.durationTicks(); elapsed++) {
      shadow.simulateMovementTick();
      if (elapsed % sampleInterval == 0 || elapsed == request.durationTicks()) {
        samples.add(shadow.position());
      }
    }
    return new PredictionPath(
        player.getUUID(),
        player.getName().getString(),
        samples,
        generation,
        request.durationTicks());
  }

  private Prediction fallback(Player player, int horizon) {
    return new Prediction(
        player.position(), player.position(), player.getBoundingBox(), 0.0f, false, horizon);
  }

  public record PredictionPath(
      UUID playerId, String playerName, List<Vec3> positions, int generation, int horizonTicks) {
    public PredictionPath {
      playerId = Objects.requireNonNull(playerId, "playerId");
      playerName = Objects.requireNonNull(playerName, "playerName");
      positions = List.copyOf(positions);
      if (positions.isEmpty())
        throw new IllegalArgumentException("A prediction path needs a position");
    }

    public Vec3 endpoint() {
      return positions.getLast();
    }
  }

  public record Prediction(
      Vec3 position,
      Vec3 estimatedPosition,
      AABB bounds,
      float confidence,
      boolean reliable,
      int horizonTicks) {}
}
