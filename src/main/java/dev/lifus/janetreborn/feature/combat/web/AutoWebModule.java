package dev.lifus.janetreborn.feature.combat.web;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.EntityGeometry;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.placement.PlacementPlan;
import dev.lifus.janetreborn.service.prediction.PredictionTiming;
import dev.lifus.janetreborn.service.prediction.Predictor;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.Notices;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class AutoWebModule extends Module {
  private static final int COORDINATOR_PRIORITY = 100;
  private static final int TARGET_MEMORY_TICKS = 80;
  private static final int TACTICAL_EXTENSION_TICKS = 20;
  private static final int REMOTE_INTERPOLATION_LEAD_TICKS = 2;
  private static final int MAX_PREDICTION_HORIZON = 10;
  private static final int PLACEMENT_RETRY_TICKS = 2;
  private static final int MINIMUM_ACTION_RESET_TICKS = 6;
  private static final double MINIMUM_HORIZONTAL_OVERLAP = 0.08;
  private static final double MINIMUM_VERTICAL_OVERLAP = 0.4;
  private static final double MINIMUM_TRACKED_MOVEMENT_SQUARED = 0.008 * 0.008;
  private static final double MAX_TRACKED_MOVEMENT_SQUARED = 1.25 * 1.25;
  private static final double MOTION_LEAD_MULTIPLIER = 1.15;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final Placement placements;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final Predictor predictions;
  private final FriendStore friends;
  private final Notices notifications;
  private final NumberSetting<Double> range =
      add(new NumberSetting<>("Range", 4.5, 2.0, 6.0, 0.1).unit("blocks"));
  private final NumberSetting<Integer> maxWebs =
      add(
          new NumberSetting<>("Maximum Webs", 2, 1, 2, 1)
              .description("Maximum cobweb placements for one tracked opponent."));
  private final BoolSetting preventSelfWeb = add(new BoolSetting("Prevent Self Web", true));
  private final Set<BlockPos> attemptedCells = new LinkedHashSet<>();
  private UUID targetId;
  private Vec3 lastObservedPosition;
  private Vec3 trackedMovement = Vec3.ZERO;
  private long expiresAt;
  private long lastObservationTick;
  private long nextPlacementTick;
  private long actionStartedTick;
  private long tick;
  private int targetPlacementsAttempted;
  private int actionPlacementsAttempted;
  private Vec3 actionOrigin;
  private BlockPos firstActionCell;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<TickEvent> clientTick = this::maintain;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<PostInputEvent> postInputTick = this::act;

  @Subscribe private final Listener<AttackEvent> attack = this::attack;

  public AutoWebModule(
      Minecraft minecraft,
      Rotations rotations,
      Placement placements,
      Hotbar hotbar,
      CombatGate coordinator,
      Predictor predictions,
      FriendStore friends,
      Notices notifications) {
    super(
        "AutoWeb",
        "Predictively places cobwebs in an opponent's movement path after combat",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.placements = Objects.requireNonNull(placements, "placements");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.predictions = Objects.requireNonNull(predictions, "predictions");
    this.friends = Objects.requireNonNull(friends, "friends");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
  }

  @Override
  protected void onDisable() {
    stop();
  }

  private void attack(AttackEvent event) {
    if (event.getTarget() instanceof Player player
        && player != minecraft.player
        && !friends.isProtected(player)) {
      boolean newTarget = !player.getUUID().equals(targetId) || tick > expiresAt;
      if (newTarget) {
        beginTarget(player);
        nextPlacementTick = tick;
      } else {
        maybeBeginNewAction(player);
      }
      targetId = player.getUUID();
      expiresAt = tick + TARGET_MEMORY_TICKS;
      if (newTarget) resetTracking(player);
      prime(player);
    }
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!ready()) {
      stop();
      return;
    }
    Player target = target();
    if (target != null) {
      observe(target);
      if (isAirborne(target) || movementIsHindered(target)) {
        expiresAt = Math.max(expiresAt, tick + TACTICAL_EXTENSION_TICKS);
      }
    }
  }

  private void act(PostInputEvent ignored) {
    Player target = target();
    int slot = hotbar.find(Items.COBWEB);
    if (target == null || slot < 0) {
      stop();
      return;
    }
    observe(target);
    maybeBeginNewAction(target);

    Set<BlockPos> currentCells =
        EntityGeometry.occupied(target, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    boolean targetInWeb =
        currentCells.stream()
            .anyMatch(position -> minecraft.level.getBlockState(position).is(Blocks.COBWEB));
    if (targetInWeb && (maxWebs.getValue() == 1 || actionPlacementsAttempted != 1)) {
      notifications.hud("AutoWeb", "Webbed " + target.getName().getString());
      stop();
      return;
    }
    if (tick < nextPlacementTick
        || !WebPlacementPolicy.mayAttempt(
            targetPlacementsAttempted, actionPlacementsAttempted, maxWebs.getValue())) {
      releaseControl();
      return;
    }

    boolean hindered = movementIsHindered(target);
    LinkedHashSet<BlockPos> placementCells = placementCells(target, currentCells, hindered);
    Optional<PlacementPlan> solved =
        placements.solveOrdered(
            placementCells,
            position ->
                !attemptedCells.contains(position)
                    && valuableForAction(position, currentCells)
                    && safeWebPosition(position));
    if (solved.isEmpty()) {
      cancelPreparation();
      return;
    }
    PlacementPlan placement = solved.get();
    if (!rotations.request(this, placement.yaw(), placement.pitch(), COORDINATOR_PRIORITY, 2)) {
      cancelPreparation();
      return;
    }
    if (!rotations.isInteractionReady(this)) {
      coordinator.release(this);
      return;
    }
    if (!placements.currentRotationHits(
        placement, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      cancelPreparation();
      return;
    }
    if (!coordinator.acquire(this, CombatAction.WEB, COORDINATOR_PRIORITY, 2)) {
      coordinator.release(this);
      return;
    }
    if (!coordinator.markAction(this)) {
      coordinator.release(this);
      return;
    }
    if (!rotations.lockRequestedRotationForNextTick(this)) {
      cancelPreparation();
      return;
    }

    PlacementPlan executionPlacement = placement;
    var result =
        hotbar.withSlot(
            slot,
            () ->
                rotations.runInteraction(
                    this,
                    executionPlacement.yaw(),
                    executionPlacement.pitch(),
                    () ->
                        minecraft.gameMode.useItemOn(
                            minecraft.player,
                            InteractionHand.MAIN_HAND,
                            executionPlacement.hit())));

    releaseControl();
    if (result.consumesAction()) {
      targetPlacementsAttempted++;
      actionPlacementsAttempted++;
      if (firstActionCell == null) {
        firstActionCell = executionPlacement.target();
        actionOrigin = target.position();
        actionStartedTick = tick;
      }
      attemptedCells.add(executionPlacement.target());
      nextPlacementTick = tick + PLACEMENT_RETRY_TICKS;
    }
  }

  private void prime(Player target) {
    if (!ready()) return;
    if (hotbar.find(Items.COBWEB) < 0) return;
    Set<BlockPos> currentCells =
        EntityGeometry.occupied(target, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    if (currentCells.stream()
        .anyMatch(position -> minecraft.level.getBlockState(position).is(Blocks.COBWEB))) return;
    if (!WebPlacementPolicy.mayAttempt(
        targetPlacementsAttempted, actionPlacementsAttempted, maxWebs.getValue())) return;
    boolean hindered = movementIsHindered(target);
    LinkedHashSet<BlockPos> placementCells = placementCells(target, currentCells, hindered);
    Optional<PlacementPlan> solved =
        placements.solveOrdered(
            placementCells,
            position ->
                !attemptedCells.contains(position)
                    && valuableForAction(position, currentCells)
                    && safeWebPosition(position));
    if (solved.isEmpty()) return;
    PlacementPlan placement = solved.get();
    if (!rotations.request(this, placement.yaw(), placement.pitch(), COORDINATOR_PRIORITY, 2))
      return;
  }

  private LinkedHashSet<BlockPos> placementCells(
      Player target, Set<BlockPos> currentCells, boolean hindered) {
    BlockPos currentCenter = feetCenter(target.getBoundingBox());
    if (hindered) {
      return WebPlacementPolicy.rankCandidates(
          currentCenter,
          currentCells,
          currentCenter,
          Set.of(),
          currentCenter,
          Set.of(),
          currentCenter,
          Set.of(),
          true);
    }

    int mainHorizon =
        Math.min(
            MAX_PREDICTION_HORIZON,
            PredictionTiming.executionHorizon(minecraft, target) + REMOTE_INTERPOLATION_LEAD_TICKS);
    AABB nearBounds = predictedBounds(target, Math.max(1, mainHorizon - 1));
    AABB mainBounds = predictedBounds(target, mainHorizon);
    AABB farBounds = predictedBounds(target, Math.min(MAX_PREDICTION_HORIZON, mainHorizon + 1));
    Set<BlockPos> nearCells =
        EntityGeometry.occupied(nearBounds, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    Set<BlockPos> mainCells =
        EntityGeometry.occupied(mainBounds, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    Set<BlockPos> farCells =
        EntityGeometry.occupied(farBounds, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    BlockPos mainCenter = feetCenter(mainBounds);

    LinkedHashSet<BlockPos> placementCells = new LinkedHashSet<>();
    if (isAirborne(target)) {
      addLandingCells(placementCells, mainCenter);
      addLandingCells(placementCells, currentCenter);
    }
    placementCells.addAll(
        WebPlacementPolicy.rankCandidates(
            currentCenter,
            currentCells,
            feetCenter(nearBounds),
            nearCells,
            mainCenter,
            mainCells,
            feetCenter(farBounds),
            farCells,
            hindered));
    return placementCells;
  }

  private boolean valuableForAction(BlockPos position, Set<BlockPos> currentCells) {
    return actionPlacementsAttempted == 0
        || (actionPlacementsAttempted == 1
            && firstActionCell != null
            && minecraft.level.getBlockState(firstActionCell).is(Blocks.COBWEB)
            && WebPlacementPolicy.isValuableSecond(firstActionCell, position, currentCells));
  }

  private void beginTarget(Player target) {
    attemptedCells.clear();
    targetPlacementsAttempted = 0;
    beginAction(target);
  }

  private void maybeBeginNewAction(Player target) {
    if (actionOrigin == null || tick - actionStartedTick < MINIMUM_ACTION_RESET_TICKS) return;
    double movementX = target.getX() - actionOrigin.x;
    double movementZ = target.getZ() - actionOrigin.z;
    double displacementSquared = movementX * movementX + movementZ * movementZ;
    if (WebPlacementPolicy.mayRestartAction(
        targetPlacementsAttempted,
        actionPlacementsAttempted,
        displacementSquared,
        maxWebs.getValue())) {
      beginAction(target);
    }
  }

  private void beginAction(Player target) {
    actionPlacementsAttempted = 0;
    firstActionCell = null;
    actionOrigin = target.position();
    actionStartedTick = tick;
  }

  private AABB predictedBounds(Player target, int horizon) {
    Predictor.Prediction prediction = predictions.predict(target, horizon);
    AABB current = target.getBoundingBox();
    double verticalOffset = prediction.bounds().minY - current.minY;
    double horizontalSpeedSquared = trackedMovement.horizontalDistanceSqr();
    if (horizontalSpeedSquared < MINIMUM_TRACKED_MOVEMENT_SQUARED) return prediction.bounds();
    double multiplier = horizon * MOTION_LEAD_MULTIPLIER;
    return current.move(
        trackedMovement.x * multiplier, verticalOffset, trackedMovement.z * multiplier);
  }

  private static BlockPos feetCenter(AABB bounds) {
    return BlockPos.containing(
        (bounds.minX + bounds.maxX) * 0.5, bounds.minY + 1.0E-4, (bounds.minZ + bounds.maxZ) * 0.5);
  }

  private static void addLandingCells(Set<BlockPos> cells, BlockPos origin) {
    for (int down = 0; down <= 3; down++) cells.add(origin.below(down).immutable());
  }

  private static boolean isAirborne(Player target) {
    return !target.onGround() || target.getDeltaMovement().y > 0.06;
  }

  private boolean movementIsHindered(Player target) {
    double horizontalSpeedSquared =
        Math.max(
            target.getDeltaMovement().horizontalDistanceSqr(),
            trackedMovement.horizontalDistanceSqr());
    return target.horizontalCollision
        || target.minorHorizontalCollision
        || horizontalSpeedSquared <= 0.035 * 0.035;
  }

  private void resetTracking(Player target) {
    Vec3 position = target.position();
    Vec3 tickMovement = horizontal(new Vec3(position.x - target.xo, 0.0, position.z - target.zo));
    Vec3 entityMovement = horizontal(target.getDeltaMovement());
    trackedMovement =
        saneMovement(tickMovement)
                && tickMovement.horizontalDistanceSqr() >= entityMovement.horizontalDistanceSqr()
            ? tickMovement
            : saneMovement(entityMovement) ? entityMovement : Vec3.ZERO;
    lastObservedPosition = position;
    lastObservationTick = tick;
  }

  private void observe(Player target) {
    Vec3 position = target.position();
    if (lastObservedPosition == null) {
      resetTracking(target);
      return;
    }
    Vec3 movement = horizontal(position.subtract(lastObservedPosition));
    if (movement.horizontalDistanceSqr() >= MINIMUM_TRACKED_MOVEMENT_SQUARED
        && saneMovement(movement)) {
      trackedMovement = trackedMovement.scale(0.55).add(movement.scale(0.45));
      lastObservedPosition = position;
      lastObservationTick = tick;
      return;
    }
    if (lastObservationTick != tick) {
      trackedMovement = trackedMovement.scale(0.7);
      lastObservationTick = tick;
    }
    lastObservedPosition = position;
  }

  private static Vec3 horizontal(Vec3 movement) {
    return new Vec3(movement.x, 0.0, movement.z);
  }

  private static boolean saneMovement(Vec3 movement) {
    return Double.isFinite(movement.x)
        && Double.isFinite(movement.z)
        && movement.horizontalDistanceSqr() <= MAX_TRACKED_MOVEMENT_SQUARED;
  }

  private Player target() {
    if (!ready() || targetId == null || tick > expiresAt) return null;
    Player player = minecraft.level.getPlayerByUUID(targetId);
    return player != null
            && player != minecraft.player
            && player instanceof AbstractClientPlayer
            && player.isAlive()
            && !player.isDeadOrDying()
            && !player.isSpectator()
            && !friends.isProtected(player)
            && minecraft.player.distanceToSqr(player) <= range.getValue() * range.getValue()
        ? player
        : null;
  }

  private boolean safeWebPosition(BlockPos position) {
    if (!preventSelfWeb.getValue()) return true;
    AABB web = new AABB(position);
    AABB playerBounds = minecraft.player.getBoundingBox();
    return !web.intersects(playerBounds)
        && !web.intersects(playerBounds.move(minecraft.player.getDeltaMovement()));
  }

  private boolean ready() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.gui.screen() == null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.player.isPassenger()
        && !minecraft.player.isHandsBusy()
        && !minecraft.player.isUsingItem();
  }

  private void releaseControl() {
    rotations.clear(this);
    coordinator.release(this);
  }

  private void cancelPreparation() {
    releaseControl();
  }

  private void stop() {
    targetId = null;
    attemptedCells.clear();
    targetPlacementsAttempted = 0;
    actionPlacementsAttempted = 0;
    firstActionCell = null;
    actionOrigin = null;
    actionStartedTick = 0;
    nextPlacementTick = 0;
    lastObservedPosition = null;
    trackedMovement = Vec3.ZERO;
    cancelPreparation();
  }
}
