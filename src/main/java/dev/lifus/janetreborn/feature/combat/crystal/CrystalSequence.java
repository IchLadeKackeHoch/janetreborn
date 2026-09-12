package dev.lifus.janetreborn.feature.combat.crystal;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

final class CrystalSequence {
  private static final int CONFIRMATION_TIMEOUT_TICKS = 6;
  private final DelaySource delays;
  private Phase phase = Phase.IDLE;
  private Configuration configuration;
  private long readyAt;
  private long confirmationExpiresAt;
  private long sequenceExpiresAt;
  private long cooldownExpiresAt;

  CrystalSequence() {
    this(
        (minimum, maximum) ->
            ThreadLocalRandom.current().nextInt(minimum, Math.addExact(maximum, 1)));
  }

  CrystalSequence(DelaySource delays) {
    this.delays = Objects.requireNonNull(delays, "delays");
  }

  boolean begin(long tick, boolean needsBase, Configuration configuration) {
    Objects.requireNonNull(configuration, "configuration");
    if (phase != Phase.IDLE) return false;
    this.configuration = configuration;
    sequenceExpiresAt = tick + configuration.sequenceTimeoutTicks();
    enterDelay(needsBase ? Phase.BASE_DELAY : Phase.CRYSTAL_DELAY, tick);
    return true;
  }

  void tick(long tick) {
    if (phase == Phase.COOLDOWN) {
      if (tick >= cooldownExpiresAt) reset();
      return;
    }
    if (!isActive()) return;
    if (tick > sequenceExpiresAt || (isConfirmationPhase() && tick > confirmationExpiresAt)) {
      abort(tick);
    }
  }

  boolean isReady(long tick) {
    return isDelayPhase() && tick >= readyAt && tick <= sequenceExpiresAt;
  }

  boolean acceptBaseAttempt(long tick) {
    if (phase != Phase.BASE_DELAY || !isReady(tick)) return false;
    enterConfirmation(Phase.BASE_CONFIRMATION, tick);
    return true;
  }

  boolean confirmBase(long tick) {
    if (phase != Phase.BASE_CONFIRMATION || tick > confirmationExpiresAt) return false;
    enterDelay(Phase.CRYSTAL_DELAY, tick);
    return true;
  }

  boolean acceptCrystalAttempt(long tick) {
    if (phase != Phase.CRYSTAL_DELAY || !isReady(tick)) return false;
    enterConfirmation(Phase.CRYSTAL_CONFIRMATION, tick);
    return true;
  }

  boolean confirmCrystal(long tick) {
    if (phase != Phase.CRYSTAL_CONFIRMATION || tick > confirmationExpiresAt) return false;
    enterDelay(Phase.BREAK_DELAY, tick);
    return true;
  }

  boolean completeBreak(long tick) {
    if (phase != Phase.BREAK_DELAY || !isReady(tick)) return false;
    enterCooldown(tick);
    return true;
  }

  void abort(long tick) {
    if (phase == Phase.IDLE || phase == Phase.COOLDOWN) return;
    enterCooldown(tick);
  }

  void reset() {
    phase = Phase.IDLE;
    configuration = null;
    readyAt = 0;
    confirmationExpiresAt = 0;
    sequenceExpiresAt = 0;
    cooldownExpiresAt = 0;
  }

  Phase phase() {
    return phase;
  }

  boolean isActive() {
    return phase != Phase.IDLE && phase != Phase.COOLDOWN;
  }

  long readyAt() {
    return readyAt;
  }

  long confirmationExpiresAt() {
    return confirmationExpiresAt;
  }

  private void enterDelay(Phase phase, long tick) {
    Range bounds = configuration.delayFor(phase);
    int delay = delays.next(bounds.minimum(), bounds.maximum());
    if (delay < bounds.minimum() || delay > bounds.maximum()) {
      throw new IllegalStateException("Delay source returned an out-of-range value: " + delay);
    }
    this.phase = phase;
    readyAt = tick + delay;
    confirmationExpiresAt = 0;
  }

  private void enterConfirmation(Phase phase, long tick) {
    this.phase = phase;
    confirmationExpiresAt = Math.min(sequenceExpiresAt, tick + CONFIRMATION_TIMEOUT_TICKS);
  }

  private void enterCooldown(long tick) {
    phase = Phase.COOLDOWN;
    readyAt = 0;
    confirmationExpiresAt = 0;
    sequenceExpiresAt = 0;
    cooldownExpiresAt = tick + configuration.cooldownTicks();
  }

  private boolean isDelayPhase() {
    return phase == Phase.BASE_DELAY || phase == Phase.CRYSTAL_DELAY || phase == Phase.BREAK_DELAY;
  }

  private boolean isConfirmationPhase() {
    return phase == Phase.BASE_CONFIRMATION || phase == Phase.CRYSTAL_CONFIRMATION;
  }

  enum Phase {
    IDLE,
    BASE_DELAY,
    BASE_CONFIRMATION,
    CRYSTAL_DELAY,
    CRYSTAL_CONFIRMATION,
    BREAK_DELAY,
    COOLDOWN
  }

  record Configuration(
      int minimumBaseDelayTicks,
      int maximumBaseDelayTicks,
      int minimumCrystalDelayTicks,
      int maximumCrystalDelayTicks,
      int minimumBreakDelayTicks,
      int maximumBreakDelayTicks,
      int sequenceTimeoutTicks,
      int cooldownTicks) {
    Configuration(
        int minimumDelayTicks, int maximumDelayTicks, int sequenceTimeoutTicks, int cooldownTicks) {
      this(
          minimumDelayTicks,
          maximumDelayTicks,
          minimumDelayTicks,
          maximumDelayTicks,
          minimumDelayTicks,
          maximumDelayTicks,
          sequenceTimeoutTicks,
          cooldownTicks);
    }

    Configuration {
      if (minimumBaseDelayTicks < 0
          || maximumBaseDelayTicks < minimumBaseDelayTicks
          || minimumCrystalDelayTicks < 0
          || maximumCrystalDelayTicks < minimumCrystalDelayTicks
          || minimumBreakDelayTicks < 0
          || maximumBreakDelayTicks < minimumBreakDelayTicks
          || sequenceTimeoutTicks <= 0
          || cooldownTicks < 0) {
        throw new IllegalArgumentException("Invalid crystal sequence configuration");
      }
    }

    Range delayFor(Phase phase) {
      return switch (phase) {
        case BASE_DELAY -> new Range(minimumBaseDelayTicks, maximumBaseDelayTicks);
        case CRYSTAL_DELAY -> new Range(minimumCrystalDelayTicks, maximumCrystalDelayTicks);
        case BREAK_DELAY -> new Range(minimumBreakDelayTicks, maximumBreakDelayTicks);
        default -> throw new IllegalArgumentException("Phase has no action delay: " + phase);
      };
    }
  }

  record Range(int minimum, int maximum) {}

  @FunctionalInterface
  interface DelaySource {
    int next(int minimumInclusive, int maximumInclusive);
  }
}
