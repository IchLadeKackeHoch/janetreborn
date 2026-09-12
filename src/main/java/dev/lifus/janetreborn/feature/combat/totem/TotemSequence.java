package dev.lifus.janetreborn.feature.combat.totem;

final class TotemSequence {
  private Phase phase = Phase.IDLE;
  private long readyAt;
  private long expiresAt;

  boolean begin(long tick, int switchDelay, int confirmationTimeout) {
    if (phase != Phase.IDLE) return false;
    phase = Phase.SWITCH_DELAY;
    readyAt = tick + switchDelay;
    expiresAt = tick + confirmationTimeout;
    return true;
  }

  boolean selected(long tick, int equipDelay) {
    if (phase != Phase.SWITCH_DELAY || tick < readyAt) return false;
    phase = Phase.EQUIP_DELAY;
    readyAt = tick + equipDelay;
    return true;
  }

  boolean swapped(long tick, int confirmationTimeout) {
    if (phase != Phase.EQUIP_DELAY || tick < readyAt) return false;
    phase = Phase.CONFIRMATION;
    readyAt = 0;
    expiresAt = tick + confirmationTimeout;
    return true;
  }

  boolean confirmed(long tick, boolean restore, int restoreDelay) {
    if (phase != Phase.CONFIRMATION || tick > expiresAt) return false;
    if (!restore) {
      reset();
      return true;
    }
    phase = Phase.RESTORE_DELAY;
    readyAt = tick + restoreDelay;
    return true;
  }

  boolean ready(long tick) {
    return (phase == Phase.SWITCH_DELAY
            || phase == Phase.EQUIP_DELAY
            || phase == Phase.RESTORE_DELAY)
        && tick >= readyAt;
  }

  boolean expired(long tick) {
    return phase != Phase.IDLE && phase != Phase.RESTORE_DELAY && tick > expiresAt;
  }

  Phase phase() {
    return phase;
  }

  void reset() {
    phase = Phase.IDLE;
    readyAt = 0;
    expiresAt = 0;
  }

  enum Phase {
    IDLE,
    SWITCH_DELAY,
    EQUIP_DELAY,
    CONFIRMATION,
    RESTORE_DELAY
  }
}
