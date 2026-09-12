package dev.lifus.janetreborn.feature.combat.aim;

public final class AimMath {
  private AimMath() {}

  public static AimRotation rotationsTo(
      double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
    double x = toX - fromX;
    double y = toY - fromY;
    double z = toZ - fromZ;
    return new AimRotation(
        (float) Math.toDegrees(Math.atan2(z, x)) - 90.0f,
        (float) -Math.toDegrees(Math.atan2(y, Math.hypot(x, z))));
  }

  public static float angularDistance(float fromYaw, float fromPitch, AimRotation target) {
    return (float) Math.hypot(wrapDegrees(target.yaw() - fromYaw), target.pitch() - fromPitch);
  }

  public static float wrapDegrees(float value) {
    if (!Float.isFinite(value)) return 0.0f;
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }

  public static float clampPitch(float value) {
    return Float.isFinite(value) ? Math.max(-90.0f, Math.min(90.0f, value)) : 0.0f;
  }
}
