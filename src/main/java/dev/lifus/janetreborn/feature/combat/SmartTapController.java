package dev.lifus.janetreborn.feature.combat;

import java.util.UUID;

final class SmartTapController {
  enum State {
    IDLE,
    CREATE_SPACE,
    HOLD_SPACING,
    REENGAGE
  }

  enum Action {
    PASS,
    RELEASE_APPROACH,
    MOVE_AWAY,
    MOVE_TOWARD
  }

  enum Intent {
    APPROACHING,
    RETREATING,
    STRAFING,
    NEUTRAL
  }

  record Observation(
      UUID targetId,
      double distance,
      double attackRange,
      double rangeVelocity,
      Intent intent,
      boolean canMove,
      boolean movementClaimed) {}

  record Decision(State state, Action action, double sideBias) {
    private static Decision pass(State state) {
      return new Decision(state, Action.PASS, 0.0);
    }
  }

  private State state = State.IDLE;
  private UUID targetId;
  private int stateTicks;
  private int ticksSinceAttack = Integer.MAX_VALUE;
  private int latencyTicks;
  private int variationSequence;
  private int variationSlot;

  void confirmedAttack(UUID attackedTargetId, boolean validOpponent, int latency) {
    if (!validOpponent || attackedTargetId == null) return;
    if (attackedTargetId.equals(targetId) && ticksSinceAttack == 0) return;

    targetId = attackedTargetId;
    state = State.CREATE_SPACE;
    stateTicks = 0;
    ticksSinceAttack = 0;
    latencyTicks = clamp(latency, 0, 2);
    variationSlot = Math.floorMod(variationSequence++, 6);
  }

  Decision tick(Observation observation, int intensity) {
    if (ticksSinceAttack < Integer.MAX_VALUE) ticksSinceAttack++;
    if (state == State.IDLE) return Decision.pass(state);
    if (observation == null
        || observation.targetId() == null
        || !observation.targetId().equals(targetId)
        || !finite(observation)
        || !observation.canMove()
        || observation.movementClaimed()) {
      reset();
      return Decision.pass(state);
    }
    if (state == State.REENGAGE && observation.intent() == Intent.RETREATING) {
      reset();
      return Decision.pass(state);
    }

    stateTicks++;
    Tuning tuning = Tuning.from(intensity, observation.attackRange(), latencyTicks);
    return switch (state) {
      case IDLE -> Decision.pass(state);
      case CREATE_SPACE -> createSpace(observation, tuning);
      case HOLD_SPACING -> holdSpacing(observation, tuning);
      case REENGAGE -> reengage(observation, tuning);
    };
  }

  State state() {
    return state;
  }

  UUID targetId() {
    return targetId;
  }

  void reset() {
    state = State.IDLE;
    targetId = null;
    stateTicks = 0;
    latencyTicks = 0;
  }

  private Decision createSpace(Observation observation, Tuning tuning) {
    if (observation.distance() >= tuning.outerDistance()) {
      transition(State.REENGAGE);
      return observation.intent() == Intent.RETREATING
          ? Decision.pass(state)
          : new Decision(state, Action.MOVE_TOWARD, 0.0);
    }
    if (observation.rangeVelocity() >= tuning.separatingVelocity()) {
      transition(State.HOLD_SPACING);
      return Decision.pass(state);
    }
    if (observation.intent() == Intent.RETREATING) {
      if (stateTicks >= tuning.maximumCreateTicks()) transition(State.HOLD_SPACING);
      return Decision.pass(state);
    }
    if (stateTicks >= tuning.minimumCreateTicks()
        && observation.distance() >= tuning.preferredDistance()
        && observation.rangeVelocity() > -tuning.rushVelocity() * 0.5) {
      transition(State.HOLD_SPACING);
      return Decision.pass(state);
    }

    boolean close = observation.distance() < tuning.innerDistance();
    boolean rushing = observation.rangeVelocity() < -tuning.rushVelocity();
    boolean variedTap =
        tuning.intensity() >= 35
            && (variationSlot == 1
                || variationSlot == 2
                || variationSlot == 4
                || tuning.intensity() >= 70 && variationSlot == 5);
    Action action = close || rushing || variedTap ? Action.MOVE_AWAY : Action.RELEASE_APPROACH;
    double sideBias = action == Action.MOVE_AWAY ? sideBias(tuning) : 0.0;

    if (stateTicks >= tuning.maximumCreateTicks()) transition(State.HOLD_SPACING);
    return new Decision(state, action, sideBias);
  }

  private Decision holdSpacing(Observation observation, Tuning tuning) {
    if (observation.intent() == Intent.RETREATING) {
      if (stateTicks >= tuning.maximumHoldTicks()) transition(State.REENGAGE);
      return Decision.pass(state);
    }

    boolean close = observation.distance() < tuning.innerDistance();
    boolean rushing = observation.rangeVelocity() < -tuning.rushVelocity();
    if (close || rushing) {
      if (stateTicks < tuning.maximumHoldTicks()) {
        return new Decision(state, Action.MOVE_AWAY, sideBias(tuning));
      }
      reset();
      return Decision.pass(state);
    }
    if (stateTicks >= tuning.minimumHoldTicks()
        || observation.distance() > tuning.preferredDistance() + 0.08) {
      transition(State.REENGAGE);
      return new Decision(state, Action.MOVE_TOWARD, 0.0);
    }
    return new Decision(state, Action.RELEASE_APPROACH, 0.0);
  }

  private Decision reengage(Observation observation, Tuning tuning) {
    boolean tooClose = observation.distance() <= tuning.innerDistance();
    boolean rushing = observation.rangeVelocity() < -tuning.rushVelocity();
    if (tooClose || rushing || stateTicks > tuning.reengageTicks()) {
      reset();
      return Decision.pass(state);
    }
    return new Decision(state, Action.MOVE_TOWARD, 0.0);
  }

  private double sideBias(Tuning tuning) {
    if (tuning.intensity() < 45) return 0.0;
    double magnitude = 0.42 + 0.16 * tuning.scale();
    if (variationSlot == 2) return magnitude;
    if (variationSlot == 5) return -magnitude;
    return 0.0;
  }

  private void transition(State next) {
    state = next;
    stateTicks = 0;
  }

  private static boolean finite(Observation observation) {
    return Double.isFinite(observation.distance())
        && Double.isFinite(observation.attackRange())
        && Double.isFinite(observation.rangeVelocity())
        && observation.distance() >= 0.0
        && observation.attackRange() > 0.0
        && observation.intent() != null;
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private record Tuning(
      int intensity,
      double scale,
      double preferredDistance,
      double innerDistance,
      double outerDistance,
      double rushVelocity,
      double separatingVelocity,
      int minimumCreateTicks,
      int maximumCreateTicks,
      int minimumHoldTicks,
      int maximumHoldTicks,
      int reengageTicks) {
    private static Tuning from(int rawIntensity, double rawAttackRange, int latencyTicks) {
      int intensity = clamp(rawIntensity, 1, 100);
      double scale = (intensity - 1) / 99.0;
      double attackRange = Math.max(2.0, Math.min(6.0, rawAttackRange));
      double preferred = attackRange - (0.45 - 0.18 * scale);
      double inner = preferred - (0.30 - 0.08 * scale);
      double outer = Math.min(attackRange - 0.05, preferred + 0.24);
      int minimumCreate = intensity < 45 ? 1 : 2;
      int maximumCreate = 2 + (int) Math.round(2.0 * scale) + latencyTicks;
      int minimumHold = 1 + (int) Math.round(scale);
      int maximumHold = minimumHold + 1 + latencyTicks;
      int reengage = 1 + (int) Math.round(scale);
      return new Tuning(
          intensity,
          scale,
          preferred,
          inner,
          outer,
          0.055 - 0.015 * scale,
          0.09 + 0.03 * scale,
          minimumCreate,
          maximumCreate,
          minimumHold,
          maximumHold,
          reengage);
    }
  }
}
