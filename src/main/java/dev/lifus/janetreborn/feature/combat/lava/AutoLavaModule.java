package dev.lifus.janetreborn.feature.combat.lava;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.AttackEvent;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.EntityGeometry;
import dev.lifus.janetreborn.service.placement.FluidAim;
import dev.lifus.janetreborn.service.placement.FluidPlan;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class AutoLavaModule extends Module {
  private static final int PLACE_COORDINATOR_PRIORITY = 140;
  private static final int PICKUP_COORDINATOR_PRIORITY = 200;
  private static final int HIT_RESPONSE_TICKS = 80;
  private static final int IGNITION_TIMEOUT_TICKS = 12;
  private static final double MINIMUM_HORIZONTAL_OVERLAP = 0.06;
  private static final double MINIMUM_VERTICAL_OVERLAP = 0.4;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final Placement placements;
  private final FluidAim fluids;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final Predictor predictions;
  private final FriendStore friends;
  private final Notices notifications;
  private final NumberSetting<Double> range =
      add(new NumberSetting<>("range", "Target Range", 4.5, 2.0, 4.5, 0.1).unit("blocks"));
  private UUID targetId;
  private Phase phase = Phase.SEEKING;
  private BlockPos lavaPosition;
  private long placedAt;
  private long expiresAt;
  private long targetSequence;
  private long placedTargetSequence;
  private long tick;

  @Subscribe private final Listener<TickEvent> clientTick = this::maintain;
  @Subscribe private final Listener<PostInputEvent> postInputTick = this::act;
  @Subscribe private final Listener<AttackEvent> attack = this::attack;

  public AutoLavaModule(
      Minecraft minecraft,
      Rotations rotations,
      Placement placements,
      FluidAim fluids,
      Hotbar hotbar,
      CombatGate coordinator,
      Predictor predictions,
      FriendStore friends,
      Notices notifications) {
    super(
        "AutoLava",
        "Immediately places and holds lava on a non-burning opponent until ignition",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.placements = Objects.requireNonNull(placements, "placements");
    this.fluids = Objects.requireNonNull(fluids, "fluids");
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
        && !friends.isProtected(player)
        && !player.isOnFire()) {
      targetId = player.getUUID();
      expiresAt = tick + HIT_RESPONSE_TICKS;
      targetSequence++;
    }
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!ready()) stop();
  }

  private void act(PostInputEvent ignored) {
    if (!ready()) return;
    if (phase == Phase.PICKUP) {
      pickup();
      return;
    }
    if (phase == Phase.IGNITING) {
      awaitIgnition();
      return;
    }

    Player target = target();
    if (target == null || target.isOnFire() || hotbar.find(Items.LAVA_BUCKET) < 0) {
      stop();
      return;
    }

    int executionHorizon = PredictionTiming.executionHorizon(minecraft, target);
    Predictor.Prediction prediction = predictions.predict(target, executionHorizon);
    Optional<PlacementPlan> solved = solveLavaPlacement(target, prediction.bounds());
    if (solved.isEmpty()) {
      releaseControl();
      return;
    }

    PlacementPlan placement = solved.get();
    if (!rotations.request(
        this, placement.yaw(), placement.pitch(), PLACE_COORDINATOR_PRIORITY, 2)) {
      coordinator.release(this);
      return;
    }
    if (!rotations.isInteractionReady(this)) {
      coordinator.release(this);
      return;
    }
    if (!placements.currentRotationHits(
        placement, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      releaseControl();
      return;
    }
    if (!coordinator.acquire(this, CombatAction.AUTO_LAVA_PLACE, PLACE_COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)) {
      coordinator.release(this);
      return;
    }
    if (!rotations.lockRequestedRotationForNextTick(this)) {
      releaseControl();
      return;
    }

    int slot = hotbar.find(Items.LAVA_BUCKET);
    String targetName = target.getName().getString();
    var result =
        hotbar.withSlot(
            slot,
            () ->
                rotations.runInteraction(
                    this,
                    placement.yaw(),
                    placement.pitch(),
                    () -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND)));
    rotations.clear(this);
    if (!result.consumesAction()) {
      releaseControl();
      return;
    }
    lavaPosition = placement.target().immutable();
    placedAt = tick;
    placedTargetSequence = targetSequence;
    phase = Phase.IGNITING;
    coordinator.release(this);
    notifications.hud("AutoLava", "Lava placed on " + targetName);
  }

  private boolean safeLavaPosition(BlockPos position) {
    if (!isDry(position)
        || !withinPlacementRange(position)
        || !fluids.canReachFutureSource(position)) {
      return false;
    }

    AABB source = new AABB(position).inflate(0.05);
    AABB playerBounds = minecraft.player.getBoundingBox();
    return !source.intersects(playerBounds)
        && !source.intersects(playerBounds.move(minecraft.player.getDeltaMovement()));
  }

  private Optional<PlacementPlan> solveLavaPlacement(Player target, AABB predictedBounds) {
    Set<BlockPos> currentCells =
        EntityGeometry.occupied(target, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    Set<BlockPos> predictedCells =
        EntityGeometry.occupied(
            predictedBounds, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    LinkedHashSet<BlockPos> sharedCells = new LinkedHashSet<>();
    for (BlockPos current : currentCells) {
      if (predictedCells.contains(current)) sharedCells.add(current);
    }
    Optional<PlacementPlan> shared = placements.solve(sharedCells, this::safeLavaPosition);
    if (shared.isPresent()) return shared;

    LinkedHashSet<BlockPos> currentTier = new LinkedHashSet<>();
    currentTier.add(
        BlockPos.containing(
            (target.getBoundingBox().minX + target.getBoundingBox().maxX) * 0.5,
            target.getBoundingBox().minY + 1.0E-4,
            (target.getBoundingBox().minZ + target.getBoundingBox().maxZ) * 0.5));
    currentTier.addAll(currentCells);
    Optional<PlacementPlan> current = placements.solve(currentTier, this::safeLavaPosition);
    if (current.isPresent()) return current;

    LinkedHashSet<BlockPos> predictedTier = new LinkedHashSet<>();
    predictedTier.add(
        BlockPos.containing(
            (predictedBounds.minX + predictedBounds.maxX) * 0.5,
            predictedBounds.minY + 1.0E-4,
            (predictedBounds.minZ + predictedBounds.maxZ) * 0.5));
    predictedTier.addAll(predictedCells);
    return placements.solve(predictedTier, this::safeLavaPosition);
  }

  private void awaitIgnition() {
    if (lavaPosition == null) {
      phase = Phase.SEEKING;
      return;
    }
    Player target = target();
    var fluid = minecraft.level.getFluidState(lavaPosition);
    boolean sourceVisible = fluid.is(FluidTags.LAVA) && fluid.isSource();
    if (target != null && target.isOnFire()) {
      notifications.hud("AutoLava", "Ignited " + target.getName().getString());
      phase = Phase.PICKUP;
      pickup();
      return;
    }
    if (!sourceVisible) {
      releaseControl();
      if (tick - placedAt > 6) {
        lavaPosition = null;
        phase = Phase.SEEKING;
      }
      return;
    }

    boolean stillInLava =
        target != null && new AABB(lavaPosition).inflate(0.08).intersects(target.getBoundingBox());
    if (target == null || !stillInLava || tick - placedAt >= IGNITION_TIMEOUT_TICKS) {
      phase = Phase.PICKUP;
      pickup();
      return;
    }
    releaseControl();
  }

  private boolean withinPlacementRange(BlockPos position) {
    Vec3 center = Vec3.atCenterOf(position);
    return minecraft.player.getEyePosition().distanceToSqr(center)
        <= range.getValue() * range.getValue();
  }

  private void pickup() {
    if (lavaPosition == null) {
      stop();
      return;
    }
    coordinator.acquire(this, CombatAction.AUTO_LAVA_PICKUP, PICKUP_COORDINATOR_PRIORITY, 3);
    var fluid = minecraft.level.getFluidState(lavaPosition);
    boolean sourceVisible = fluid.is(FluidTags.LAVA) && fluid.isSource();
    if (!sourceVisible) {
      rotations.clear(this);
      if (tick - placedAt > 6) stop();
      return;
    }
    FluidPlan aim = fluids.solve(lavaPosition);
    int bucketSlot = hotbar.find(Items.BUCKET);
    if (aim == null || bucketSlot < 0) {
      rotations.clear(this);
      return;
    }
    if (!rotations.request(this, aim.yaw(), aim.pitch(), PICKUP_COORDINATOR_PRIORITY, 2)) {
      coordinator.release(this);
      return;
    }
    if (tick <= placedAt || !rotations.isInteractionReady(this)) {
      coordinator.acquire(this, CombatAction.AUTO_LAVA_PICKUP, PICKUP_COORDINATOR_PRIORITY, 3);
      return;
    }
    if (!fluids.rayHits(aim, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      releaseControl();
      return;
    }
    if (!coordinator.markAction(this)) {
      rotations.clear(this);
      return;
    }
    if (!rotations.lockRequestedRotationForNextTick(this)) {
      releaseControl();
      return;
    }
    var result =
        hotbar.withSlot(
            bucketSlot,
            () ->
                rotations.runInteraction(
                    this,
                    aim.yaw(),
                    aim.pitch(),
                    () -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND)));
    rotations.clear(this);
    if (result.consumesAction()) finishPickup();
  }

  private boolean isDry(BlockPos position) {
    for (int x = -1; x <= 1; x++) {
      for (int y = -1; y <= 1; y++) {
        for (int z = -1; z <= 1; z++) {
          if (minecraft.level.getFluidState(position.offset(x, y, z)).is(FluidTags.WATER))
            return false;
        }
      }
    }
    return true;
  }

  private Player target() {
    if (targetId == null || tick > expiresAt) return null;
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

  private void stop() {
    targetId = null;
    lavaPosition = null;
    phase = Phase.SEEKING;
    releaseControl();
  }

  private void finishPickup() {
    boolean queuedTarget =
        targetId != null && targetSequence > placedTargetSequence && tick <= expiresAt;
    Player currentTarget = target();
    boolean retryCurrentTarget = currentTarget != null && !currentTarget.isOnFire();
    lavaPosition = null;
    phase = Phase.SEEKING;
    releaseControl();
    if (!queuedTarget && !retryCurrentTarget) targetId = null;
  }

  private enum Phase {
    SEEKING,
    IGNITING,
    PICKUP
  }
}
