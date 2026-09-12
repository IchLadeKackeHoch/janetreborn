package dev.lifus.janetreborn.feature.combat.aim;

public final class AimActivationPolicy {
  private AimActivationPolicy() {}

  public static boolean shouldAssist(boolean manualAttackHeld, boolean triggerbotEnabled) {
    return manualAttackHeld || triggerbotEnabled;
  }
}
