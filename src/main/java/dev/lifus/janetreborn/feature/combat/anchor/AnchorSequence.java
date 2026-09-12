package dev.lifus.janetreborn.feature.combat.anchor;

final class AnchorSequence {
  private Phase phase = Phase.IDLE;
  private long readyAt;
  private long expiresAt;

  boolean begin(long tick, boolean needsCharge, int switchDelay, int timeout) {
    if (phase != Phase.IDLE) return false;
    enterActive(
        needsCharge ? Phase.CHARGE_SWITCH_DELAY : Phase.DETONATE_SWITCH_DELAY,
        tick,
        switchDelay,
        timeout);
    return true;
  }

  boolean switched(long tick, int actionDelay, int timeout) {
    if (!ready(tick)) return false;
    if (phase == Phase.CHARGE_SWITCH_DELAY)
      enterActive(Phase.CHARGE_DELAY, tick, actionDelay, timeout);
    else if (phase == Phase.DETONATE_SWITCH_DELAY)
      enterActive(Phase.DETONATE_DELAY, tick, actionDelay, timeout);
    else return false;
    return true;
  }

  boolean attempted(long tick, int confirmationTimeout) {
    if (!ready(tick)) return false;
    if (phase == Phase.CHARGE_DELAY) phase = Phase.CHARGE_CONFIRMATION;
    else if (phase == Phase.DETONATE_DELAY) phase = Phase.DETONATE_CONFIRMATION;
    else return false;
    readyAt = 0;
    expiresAt = tick + confirmationTimeout;
    return true;
  }

  boolean chargeConfirmed(
      long tick, boolean chargeOnly, int switchDelay, int cooldown, int timeout) {
    if (phase != Phase.CHARGE_CONFIRMATION || tick > expiresAt) return false;
    if (chargeOnly) enter(Phase.COOLDOWN, tick, cooldown);
    else enterActive(Phase.DETONATE_SWITCH_DELAY, tick, switchDelay, timeout);
    return true;
  }

  boolean detonationConfirmed(
      long tick, boolean replace, int replaceDelay, int cooldown, int timeout) {
    if (phase != Phase.DETONATE_CONFIRMATION || tick > expiresAt) return false;
    if (replace) enterActive(Phase.REPLACE_DELAY, tick, replaceDelay, timeout);
    else enter(Phase.COOLDOWN, tick, cooldown);
    return true;
  }

  boolean replacementComplete(long tick, int cooldown) {
    if (phase != Phase.REPLACE_DELAY || tick < readyAt) return false;
    enter(Phase.COOLDOWN, tick, cooldown);
    return true;
  }

  void tick(long tick) {
    if (phase == Phase.COOLDOWN && tick >= readyAt) reset();
    else if (active() && tick > expiresAt) reset();
  }

  boolean ready(long tick) {
    return isDelay() && tick >= readyAt;
  }

  Phase phase() {
    return phase;
  }

  boolean active() {
    return phase != Phase.IDLE && phase != Phase.COOLDOWN;
  }

  void reset() {
    phase = Phase.IDLE;
    readyAt = 0;
    expiresAt = 0;
  }

  private void enter(Phase phase, long tick, int delay) {
    this.phase = phase;
    readyAt = tick + delay;
  }

  private void enterActive(Phase phase, long tick, int delay, int timeout) {
    enter(phase, tick, delay);
    expiresAt = tick + delay + timeout;
  }

  private boolean isDelay() {
    return phase == Phase.CHARGE_SWITCH_DELAY
        || phase == Phase.CHARGE_DELAY
        || phase == Phase.DETONATE_SWITCH_DELAY
        || phase == Phase.DETONATE_DELAY
        || phase == Phase.REPLACE_DELAY;
  }

  enum Phase {
    IDLE,
    CHARGE_SWITCH_DELAY,
    CHARGE_DELAY,
    CHARGE_CONFIRMATION,
    DETONATE_SWITCH_DELAY,
    DETONATE_DELAY,
    DETONATE_CONFIRMATION,
    REPLACE_DELAY,
    COOLDOWN
  }
}
