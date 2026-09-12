package dev.lifus.janetreborn.feature.combat;

import dev.codeman.eventbusx.Priority;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.MovementInputEvent;
import dev.lifus.janetreborn.feature.combat.SmartTapController.Action;
import dev.lifus.janetreborn.feature.combat.SmartTapController.Decision;
import dev.lifus.janetreborn.feature.combat.SmartTapController.Intent;
import dev.lifus.janetreborn.feature.combat.SmartTapController.Observation;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class SmartTapModule extends Module {
  private static final int MOVEMENT_PRIORITY = Priority.LOWEST - 1;
  private static final double MAX_TRACKING_MARGIN = 1.0;
  private static final double MIN_HORIZONTAL_SQUARED = 1.0E-5;

  private final Minecraft minecraft;
  private final FriendStore friends;
  private final CombatGate combat;
  private final NumberSetting<Integer> intensity =
      add(
          new NumberSetting<>("Intensity", 55, 1, 100, 1)
              .unit("%")
              .description("Controls how strongly and how long SmartTap creates spacing."));
  private final SmartTapController controller = new SmartTapController();

  public SmartTapModule(Minecraft minecraft, FriendStore friends, CombatGate combat) {
    super(
        "SmartTap",
        "Creates useful spacing after confirmed hits and promptly re-engages",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.friends = Objects.requireNonNull(friends, "friends");
    this.combat = Objects.requireNonNull(combat, "combat");
  }

  @Override
  protected void onInitialize() {
    listen(AttackEvent.class, this::onAttack);
    listen(MovementInputEvent.class, MOVEMENT_PRIORITY, this::onMovementInput);
  }

  @Override
  protected void onEnable() {
    controller.reset();
  }

  @Override
  protected void onDisable() {
    controller.reset();
  }

  private void onAttack(AttackEvent event) {
    Player target = event.getTarget() instanceof Player player ? player : null;
    boolean valid = validOpponent(target, true);
    controller.confirmedAttack(
        target == null ? null : target.getUUID(), valid, latencyTicks(target));
  }

  private void onMovementInput(MovementInputEvent event) {
    LocalPlayer player = event.player();
    if (player != minecraft.player) {
      controller.reset();
      return;
    }

    Player target = activeTarget();
    Input original = event.input();
    RelativeMovement relative = relativeMovement(player, target, original);
    boolean canMove = canMoveNormally(player, original) && validOpponent(target, false);
    Observation observation =
        target == null || relative == null
            ? null
            : new Observation(
                target.getUUID(),
                hitboxDistance(player, target),
                player.entityInteractionRange(),
                relative.rangeVelocity(),
                relative.intent(),
                canMove,
                event.isClaimed());
    Decision decision = controller.tick(observation, intensity.getValue());
    if (decision.action() == Action.PASS || event.isClaimed() || relative == null) return;

    Input adjusted = adjustedInput(original, player.getYRot(), relative, decision);
    if (adjusted != original) event.claim(this, adjusted);
  }

  private Player activeTarget() {
    if (minecraft.level == null || controller.targetId() == null) return null;
    return minecraft.level.getPlayerByUUID(controller.targetId());
  }

  private boolean validOpponent(Player target, boolean requireAttackRange) {
    LocalPlayer player = minecraft.player;
    if (player == null
        || target == null
        || target == player
        || !target.isAlive()
        || target.isDeadOrDying()
        || target.isSpectator()
        || friends.isProtected(target)
        || !player.canAttack(target)) return false;
    double margin = requireAttackRange ? 0.0 : MAX_TRACKING_MARGIN;
    return player.isWithinEntityInteractionRange(target, margin);
  }

  private boolean canMoveNormally(LocalPlayer player, Input input) {
    if (minecraft.level == null
        || minecraft.gameMode == null
        || minecraft.getConnection() == null
        || minecraft.gui.screen() != null
        || minecraft.gui.overlay() != null
        || !minecraft.mouseHandler.isMouseGrabbed()
        || !player.isAlive()
        || player.isDeadOrDying()
        || player.isSleeping()
        || player.isPassenger()
        || player.onClimbable()
        || player.isFallFlying()
        || player.getAbilities().flying
        || player.noPhysics
        || player.isHandsBusy()
        || player.isUsingItem()
        || minecraft.options.keyUse.isDown()
        || isBridging(player, input)
        || minecraft.gameMode.isSpectator()
        || minecraft.gameMode.isDestroying()
        || player.horizontalCollision
        || player.isInWater()
        || player.isInLava()
        || player.isMobilityRestricted()
        || unsafeAirborne(player)
        || conflictsWithCombatAction()
        || player.hurtTime > 0) return false;
    return !(input.forward() && input.backward()) && !(input.left() && input.right());
  }

  private static boolean unsafeAirborne(LocalPlayer player) {
    return !player.onGround()
        && (player.fallDistance > 0.5f || Math.abs(player.getDeltaMovement().y) > 0.12);
  }

  private static boolean isBridging(LocalPlayer player, Input input) {
    return input.shift()
        && (player.getMainHandItem().getItem() instanceof BlockItem
            || player.getOffhandItem().getItem() instanceof BlockItem);
  }

  private boolean conflictsWithCombatAction() {
    CombatAction action = combat.activeAction();
    return action != null && action != CombatAction.TRIGGERBOT;
  }

  private static RelativeMovement relativeMovement(LocalPlayer player, Player target, Input input) {
    if (target == null) return null;
    double offsetX = target.getX() - player.getX();
    double offsetZ = target.getZ() - player.getZ();
    double distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
    if (distanceSquared < MIN_HORIZONTAL_SQUARED || !Double.isFinite(distanceSquared)) return null;

    double inverseDistance = 1.0 / Math.sqrt(distanceSquared);
    double towardX = offsetX * inverseDistance;
    double towardZ = offsetZ * inverseDistance;
    Vec3 playerVelocity = player.getDeltaMovement();
    Vec3 targetVelocity = target.getDeltaMovement();
    double rangeVelocity =
        (targetVelocity.x - playerVelocity.x) * towardX
            + (targetVelocity.z - playerVelocity.z) * towardZ;

    WorldMovement inputMovement = worldMovement(input, player.getYRot());
    double approach = inputMovement.x() * towardX + inputMovement.z() * towardZ;
    double tangent = -inputMovement.x() * towardZ + inputMovement.z() * towardX;
    Intent intent;
    if (approach > 0.30) intent = Intent.APPROACHING;
    else if (approach < -0.30) intent = Intent.RETREATING;
    else if (Math.abs(tangent) > 0.30) intent = Intent.STRAFING;
    else intent = Intent.NEUTRAL;
    return new RelativeMovement(towardX, towardZ, rangeVelocity, tangent, intent);
  }

  private static Input adjustedInput(
      Input original, float yaw, RelativeMovement relative, Decision decision) {
    if (decision.action() == Action.RELEASE_APPROACH) {
      WorldMovement movement = worldMovement(original, yaw);
      double approach = movement.x() * relative.towardX() + movement.z() * relative.towardZ();
      if (approach <= 0.0) return original;
      double x = movement.x() - relative.towardX() * approach;
      double z = movement.z() - relative.towardZ() * approach;
      return inputForVector(original, yaw, x, z, false);
    }

    boolean toward = decision.action() == Action.MOVE_TOWARD;
    double radial = toward ? 1.0 : -1.0;
    double tangentBias = userTangent(relative);
    if (Math.abs(tangentBias) < 0.1) tangentBias = decision.sideBias();
    double x = relative.towardX() * radial - relative.towardZ() * tangentBias;
    double z = relative.towardZ() * radial + relative.towardX() * tangentBias;
    return inputForVector(original, yaw, x, z, toward);
  }

  private static double userTangent(RelativeMovement relative) {
    if (Math.abs(relative.inputTangent()) <= 0.30) return 0.0;
    return Math.copySign(0.5, relative.inputTangent());
  }

  private static Input inputForVector(
      Input original, float yaw, double worldX, double worldZ, boolean sprint) {
    Direction direction = Direction.closest(worldX, worldZ, yaw);
    if (direction == Direction.NONE) {
      return new Input(false, false, false, false, original.jump(), original.shift(), false);
    }
    return new Input(
        direction.forward(),
        direction.backward(),
        direction.left(),
        direction.right(),
        original.jump(),
        original.shift(),
        sprint && (original.sprint() || direction.forward()));
  }

  private static WorldMovement worldMovement(Input input, float yaw) {
    int forward = axis(input.forward(), input.backward());
    int strafe = axis(input.left(), input.right());
    if (forward == 0 && strafe == 0) return new WorldMovement(0.0, 0.0);
    double radians = Math.toRadians(yaw);
    double x = -Math.sin(radians) * forward + Math.cos(radians) * strafe;
    double z = Math.cos(radians) * forward + Math.sin(radians) * strafe;
    double inverseLength = 1.0 / Math.sqrt(x * x + z * z);
    return new WorldMovement(x * inverseLength, z * inverseLength);
  }

  private int latencyTicks(Player target) {
    if (target == null || minecraft.getConnection() == null) return 0;
    return Math.max(
        0, Math.min(2, (int) Math.ceil((latency(minecraft.player) + latency(target)) / 100.0)));
  }

  private int latency(Player player) {
    if (player == null || minecraft.getConnection() == null) return 0;
    PlayerInfo info = minecraft.getConnection().getPlayerInfo(player.getUUID());
    return info == null ? 0 : Math.max(0, Math.min(1000, info.getLatency()));
  }

  private static double hitboxDistance(LocalPlayer player, Player target) {
    return Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition()));
  }

  private static int axis(boolean positive, boolean negative) {
    return (positive ? 1 : 0) - (negative ? 1 : 0);
  }

  private record WorldMovement(double x, double z) {}

  private record RelativeMovement(
      double towardX, double towardZ, double rangeVelocity, double inputTangent, Intent intent) {}

  private enum Direction {
    NONE(0, 0),
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

    private static Direction closest(double targetX, double targetZ, float yaw) {
      if (targetX * targetX + targetZ * targetZ < MIN_HORIZONTAL_SQUARED) return NONE;
      Direction best = FORWARD;
      double bestScore = -Double.MAX_VALUE;
      for (Direction candidate : values()) {
        if (candidate == NONE) continue;
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
  }
}
