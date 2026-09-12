package dev.lifus.janetreborn.feature.combat;

final class RetreatDetector {
  private static final double RETREAT_THRESHOLD = -0.35;
  private static final double ENGAGE_THRESHOLD = 0.35;
  private static final double MIN_DIRECTION_SQUARED = 1.0E-4;
  private static final int NEUTRAL_RELEASE_TICKS = 2;

  private boolean retreating;
  private int neutralTicks;

  boolean update(
      int forwardAxis, int strafeAxis, float yaw, double opponentOffsetX, double opponentOffsetZ) {
    double opponentLengthSquared =
        opponentOffsetX * opponentOffsetX + opponentOffsetZ * opponentOffsetZ;
    if (opponentLengthSquared < MIN_DIRECTION_SQUARED || !Double.isFinite(opponentLengthSquared)) {
      reset();
      return false;
    }

    double radians = Math.toRadians(yaw);
    double movementX = -Math.sin(radians) * forwardAxis + Math.cos(radians) * strafeAxis;
    double movementZ = Math.cos(radians) * forwardAxis + Math.sin(radians) * strafeAxis;
    double movementLengthSquared = movementX * movementX + movementZ * movementZ;
    double approach = 0.0;
    if (movementLengthSquared >= MIN_DIRECTION_SQUARED) {
      approach =
          (movementX * opponentOffsetX + movementZ * opponentOffsetZ)
              / Math.sqrt(movementLengthSquared * opponentLengthSquared);
    }

    if (approach <= RETREAT_THRESHOLD) {
      retreating = true;
      neutralTicks = 0;
    } else if (approach >= ENGAGE_THRESHOLD) {
      reset();
    } else if (retreating && ++neutralTicks >= NEUTRAL_RELEASE_TICKS) {
      reset();
    }
    return retreating;
  }

  void reset() {
    retreating = false;
    neutralTicks = 0;
  }
}
