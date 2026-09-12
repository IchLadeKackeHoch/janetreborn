package dev.lifus.janetreborn.feature.combat.crystal;

public enum CrystalActivationMode {
  MANUAL_HIT,
  CROSSHAIR_HOLD,
  BOTH;

  boolean manualHit() {
    return this != CROSSHAIR_HOLD;
  }

  boolean crosshair() {
    return this != MANUAL_HIT;
  }
}
