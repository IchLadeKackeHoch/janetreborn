package dev.lifus.janetreborn.feature.combat;

import dev.codeman.eventbusx.Priority;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.MovementInputEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class VelocityModule extends Module {
  private static final int MAX_RESPONSE_TICKS = 6;
  private static final double MIN_IMPULSE_SQUARED = 0.08 * 0.08;
  private static final double MIN_AWAY_SPEED = 0.01;
  private static final double MAX_OPPONENT_DISTANCE_SQUARED = 12.0 * 12.0;
  private static final double MAX_FALLBACK_ALIGNMENT = -0.35;

  private final Minecraft minecraft;
  private final NumberSetting<Integer> counterStrength =
      add(
          new NumberSetting<>("Counter Strength", 70, 10, 100, 5)
              .unit("%")
              .description("Controls the duration of the counter-movement response."));
  private LocalPlayer trackedPlayer;
  private Vec3 previousMovement = Vec3.ZERO;
  private boolean hasPreviousMovement;
  private int previousHurtTime;
  private int detectionTicks;
  private int responseTicks;
  private int actedTicks;
  private double awayX;
  private double awayZ;
  private final RetreatDetector retreatDetector = new RetreatDetector();
  private UUID recentCombatTargetId;
  private UUID responseOpponentId;

  public VelocityModule(Minecraft minecraft) {
    super(
        "Velocity",
        "Counters incoming knockback with ordinary movement, sprint, and jump timing",
        Category.COMBAT);
    this.minecraft = minecraft;
  }

  @Override
  protected void onInitialize() {
    listen(MovementInputEvent.class, Priority.LOWEST, this::applyCounterMovement);
    listen(AttackEvent.class, this::rememberCombatTarget);
    listen(TickEndEvent.class, this::rememberMovement);
  }

  @Override
  protected void onEnable() {
    reset();
  }

  @Override
  protected void onDisable() {
    reset();
  }

  private void applyCounterMovement(MovementInputEvent event) {
    LocalPlayer player = event.player();
    if (player != minecraft.player || player != trackedPlayer) {
      beginTracking(player);
    }

    int hurtTime = player.hurtTime;
    boolean newHit = hurtTime > 0 && hurtTime >= previousHurtTime;
    previousHurtTime = hurtTime;

    if (!canAct(player) || event.isClaimed()) {
      detectionTicks = 0;
      clearResponse();
      return;
    }

    if (newHit) detectionTicks = 2;
    if (detectionTicks > 0) {
      if (startResponse(player.getDeltaMovement())) detectionTicks = 0;
      else detectionTicks--;
    }
    if (responseTicks <= 0) return;

    if (isRetreating(event.input(), player)) {
      if (--responseTicks == 0) clearResponse();
      return;
    }

    Vec3 movement = player.getDeltaMovement();
    double awaySpeed = movement.x * awayX + movement.z * awayZ;
    if (actedTicks > 0 && awaySpeed <= MIN_AWAY_SPEED) {
      clearResponse();
      return;
    }

    Input original = event.input();
    Direction direction = closestInput(-awayX, -awayZ, player.getYRot());
    boolean inLiquid = player.isInWater() || player.isInLava();
    boolean sprint = original.sprint() || direction.forward();
    boolean jump = inLiquid && original.jump();
    Input counter =
        new Input(
            direction.forward(),
            direction.backward(),
            direction.left(),
            direction.right(),
            jump,
            original.shift(),
            sprint);
    if (!event.claim(this, counter)) {
      detectionTicks = 0;
      clearResponse();
      return;
    }

    responseTicks--;
    actedTicks++;
    if (responseTicks == 0) clearResponse();
  }

  private void rememberMovement(TickEndEvent ignored) {
    LocalPlayer player = minecraft.player;
    if (player == null) {
      reset();
      return;
    }
    if (player != trackedPlayer) beginTracking(player);
    previousMovement = player.getDeltaMovement();
    hasPreviousMovement = finite(previousMovement);
  }

  private boolean startResponse(Vec3 movement) {
    if (!finite(movement)) {
      clearResponse();
      return false;
    }

    double impulseX = movement.x;
    double impulseZ = movement.z;
    if (hasPreviousMovement) {
      impulseX = movement.x - previousMovement.x * 0.5;
      impulseZ = movement.z - previousMovement.z * 0.5;
    }

    double lengthSquared = horizontalSquared(impulseX, impulseZ);
    if (lengthSquared < MIN_IMPULSE_SQUARED) return false;
    double inverseLength = 1.0 / Math.sqrt(lengthSquared);
    awayX = impulseX * inverseLength;
    awayZ = impulseZ * inverseLength;
    responseTicks =
        Math.max(1, (int) Math.ceil(MAX_RESPONSE_TICKS * counterStrength.getValue() / 100.0));
    actedTicks = 0;
    retreatDetector.reset();
    responseOpponentId = resolveOpponent(awayX, awayZ);
    return true;
  }

  private void rememberCombatTarget(AttackEvent event) {
    if (event.getTarget() instanceof Player player && player != minecraft.player) {
      recentCombatTargetId = player.getUUID();
    }
  }

  private boolean isRetreating(Input input, LocalPlayer player) {
    Player opponent = responseOpponent();
    if (opponent == null) {
      retreatDetector.reset();
      return false;
    }
    return retreatDetector.update(
        axis(input.forward(), input.backward()),
        axis(input.left(), input.right()),
        player.getYRot(),
        opponent.getX() - player.getX(),
        opponent.getZ() - player.getZ());
  }

  private UUID resolveOpponent(double impulseAwayX, double impulseAwayZ) {
    LocalPlayer player = minecraft.player;
    if (player == null || minecraft.level == null) return null;

    Entity damageSource =
        player.getLastDamageSource() == null ? null : player.getLastDamageSource().getEntity();
    if (damageSource instanceof Player sourcePlayer && isNearbyOpponent(sourcePlayer, player)) {
      return sourcePlayer.getUUID();
    }
    LivingEntity lastAttacker = player.getLastHurtByMob();
    if (lastAttacker instanceof Player attackingPlayer && isNearbyOpponent(attackingPlayer, player))
      return attackingPlayer.getUUID();

    Player recent = playerById(recentCombatTargetId);
    if (isNearbyOpponent(recent, player)) return recent.getUUID();

    Player best = null;
    double bestDistance = Double.MAX_VALUE;
    for (Player candidate : minecraft.level.players()) {
      if (!isNearbyOpponent(candidate, player)) continue;
      double offsetX = candidate.getX() - player.getX();
      double offsetZ = candidate.getZ() - player.getZ();
      double distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
      double alignment =
          (offsetX * impulseAwayX + offsetZ * impulseAwayZ) / Math.sqrt(distanceSquared);
      if (alignment <= MAX_FALLBACK_ALIGNMENT && distanceSquared < bestDistance) {
        best = candidate;
        bestDistance = distanceSquared;
      }
    }
    return best == null ? null : best.getUUID();
  }

  private Player responseOpponent() {
    Player opponent = playerById(responseOpponentId);
    return isNearbyOpponent(opponent, minecraft.player) ? opponent : null;
  }

  private Player playerById(UUID id) {
    return id == null || minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
  }

  private static boolean isOpponent(Entity candidate, Player player) {
    return candidate instanceof Player opponent
        && opponent != player
        && opponent.isAlive()
        && !opponent.isDeadOrDying()
        && !opponent.isSpectator();
  }

  private static boolean isNearbyOpponent(Player candidate, Player player) {
    return player != null
        && isOpponent(candidate, player)
        && player.distanceToSqr(candidate) <= MAX_OPPONENT_DISTANCE_SQUARED;
  }

  private boolean canAct(LocalPlayer player) {
    return minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.gui.screen() == null
        && minecraft.gui.overlay() == null
        && minecraft.mouseHandler.isMouseGrabbed()
        && player.isAlive()
        && !player.isDeadOrDying()
        && !player.isSleeping()
        && !player.isPassenger()
        && !player.onClimbable()
        && !player.isFallFlying()
        && !player.getAbilities().flying
        && !player.noPhysics
        && !minecraft.gameMode.isSpectator();
  }

  private void beginTracking(LocalPlayer player) {
    trackedPlayer = player;
    recentCombatTargetId = null;
    previousMovement = Vec3.ZERO;
    hasPreviousMovement = false;
    previousHurtTime = player.hurtTime;
    detectionTicks = 0;
    clearResponse();
  }

  private void reset() {
    trackedPlayer = null;
    recentCombatTargetId = null;
    previousMovement = Vec3.ZERO;
    hasPreviousMovement = false;
    previousHurtTime = 0;
    detectionTicks = 0;
    clearResponse();
  }

  private void clearResponse() {
    responseTicks = 0;
    actedTicks = 0;
    awayX = 0.0;
    awayZ = 0.0;
    responseOpponentId = null;
    retreatDetector.reset();
  }

  private static int axis(boolean positive, boolean negative) {
    return (positive ? 1 : 0) - (negative ? 1 : 0);
  }

  private static Direction closestInput(double targetX, double targetZ, float yaw) {
    Direction best = Direction.FORWARD;
    double bestScore = -Double.MAX_VALUE;
    for (Direction candidate : Direction.values()) {
      double angle =
          Math.toRadians(
              yaw + Math.toDegrees(Math.atan2(-candidate.strafe, candidate.forwardAxis)));
      double worldX = -Math.sin(angle);
      double worldZ = Math.cos(angle);
      double score = targetX * worldX + targetZ * worldZ;
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  private static boolean finite(Vec3 movement) {
    return Double.isFinite(movement.x)
        && Double.isFinite(movement.y)
        && Double.isFinite(movement.z);
  }

  private static double horizontalSquared(double x, double z) {
    return x * x + z * z;
  }

  private enum Direction {
    FORWARD(1, 0),
    FORWARD_LEFT(1, 1),
    LEFT(0, 1),
    BACKWARD_LEFT(-1, 1),
    BACKWARD(-1, 0),
    BACKWARD_RIGHT(-1, -1),
    RIGHT(0, -1),
    FORWARD_RIGHT(1, -1);

    private final int forwardAxis;
    private final int strafe;

    Direction(int forwardAxis, int strafe) {
      this.forwardAxis = forwardAxis;
      this.strafe = strafe;
    }

    private boolean forward() {
      return forwardAxis > 0;
    }

    private boolean backward() {
      return forwardAxis < 0;
    }

    private boolean left() {
      return strafe > 0;
    }

    private boolean right() {
      return strafe < 0;
    }
  }
}
