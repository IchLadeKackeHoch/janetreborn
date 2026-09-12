package dev.lifus.janetreborn.feature.combat.aim;

public final class AimIntentDetector {
  private static final float ERROR_GROWTH_EPSILON = 0.05f;
  private static final float DISENGAGEMENT_THRESHOLD = 1.5f;

  public Disengagement evaluate(
      float manualYaw, float manualPitch, float errorBeforeInput, float errorAfterInput) {
    float input = (float) Math.hypot(manualYaw, manualPitch);
    if (input >= DISENGAGEMENT_THRESHOLD * 3.0f) return Disengagement.RAPID_CAMERA_MOVEMENT;
    if (input >= DISENGAGEMENT_THRESHOLD
        && errorAfterInput > errorBeforeInput + ERROR_GROWTH_EPSILON) {
      return Disengagement.CONFLICTING_INPUT;
    }
    return Disengagement.NONE;
  }

  public enum Disengagement {
    NONE,
    CONFLICTING_INPUT,
    RAPID_CAMERA_MOVEMENT
  }
}
