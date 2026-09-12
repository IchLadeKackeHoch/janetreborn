package dev.lifus.janetreborn.service.prediction;

public record PredictionConfig(int durationTicks, int simulationStepTicks, int trailLength) {
  public static final int MAX_DURATION_TICKS = 200;
  public static final PredictionConfig DEFAULT = new PredictionConfig(20, 1, 21);

  public PredictionConfig {
    durationTicks = Math.max(0, Math.min(MAX_DURATION_TICKS, durationTicks));
    simulationStepTicks = Math.max(1, Math.min(MAX_DURATION_TICKS, simulationStepTicks));
    trailLength = Math.max(2, Math.min(MAX_DURATION_TICKS + 1, trailLength));
  }
}
