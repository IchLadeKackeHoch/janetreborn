package dev.lifus.janetreborn.feature.combat.crystal;

final class CrystalTriggerPolicy {
  private CrystalTriggerPolicy() {}

  static boolean mayArm(
      boolean idle,
      boolean playerTarget,
      boolean physicalAttack,
      boolean clientReady,
      boolean targetValid,
      boolean swordRequired,
      boolean swordHeld,
      boolean healthy) {
    return idle
        && playerTarget
        && physicalAttack
        && clientReady
        && targetValid
        && (!swordRequired || swordHeld)
        && healthy;
  }
}
