package dev.lifus.janetreborn.feature.combat.aim;

public final class AimController {
  private static final float MIN_TIME_CONSTANT_SECONDS = 0.018f;
  private static final float MAX_TIME_CONSTANT_SECONDS = 0.55f;
  private static final float SMOOTHING_PERCENT = 52.0f;
  private static final float MAXIMUM_DEGREES_PER_SECOND = 360.0f;

  public AimRotation step(
      float currentYaw,
      float currentPitch,
      AimRotation target,
      float deltaSeconds,
      float strengthPercent) {
    float dt = clamp(deltaSeconds, 1.0f / 500.0f, 0.05f);
    float strength = clamp(strengthPercent / 100.0f, 0.0f, 1.0f);
    float smoothing = SMOOTHING_PERCENT / 100.0f;
    float timeConstant =
        MIN_TIME_CONSTANT_SECONDS
            + smoothing * smoothing * (MAX_TIME_CONSTANT_SECONDS - MIN_TIME_CONSTANT_SECONDS);
    float response = (float) (1.0 - Math.exp(-dt / timeConstant)) * strength;

    float yawError = AimMath.wrapDegrees(target.yaw() - currentYaw);
    float pitchError = target.pitch() - currentPitch;
    float requestedYaw = yawError * response;
    float requestedPitch = pitchError * response;
    float requestedLength = (float) Math.hypot(requestedYaw, requestedPitch);
    float maximumStep = MAXIMUM_DEGREES_PER_SECOND * strength * dt;
    if (requestedLength > maximumStep && requestedLength > 0.0f) {
      float scale = maximumStep / requestedLength;
      requestedYaw *= scale;
      requestedPitch *= scale;
    }

    return new AimRotation(
        AimMath.wrapDegrees(currentYaw + requestedYaw),
        AimMath.clampPitch(currentPitch + requestedPitch));
  }

  private static float clamp(float value, float minimum, float maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
