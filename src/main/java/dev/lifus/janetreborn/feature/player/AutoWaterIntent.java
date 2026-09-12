package dev.lifus.janetreborn.feature.player;

public enum AutoWaterIntent {
  NONE,
  FIRE,
  COBWEB,
  FIRE_AND_COBWEB;

  public static AutoWaterIntent decide(boolean burning, boolean inCobweb, boolean autoFree) {
    boolean escapeWeb = autoFree && inCobweb;
    if (burning && escapeWeb) return FIRE_AND_COBWEB;
    if (burning) return FIRE;
    if (escapeWeb) return COBWEB;
    return NONE;
  }

  public boolean includesFire() {
    return this == FIRE || this == FIRE_AND_COBWEB;
  }

  public boolean includesCobweb() {
    return this == COBWEB || this == FIRE_AND_COBWEB;
  }
}
