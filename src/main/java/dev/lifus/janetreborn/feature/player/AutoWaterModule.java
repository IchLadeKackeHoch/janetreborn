package dev.lifus.janetreborn.feature.player;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.EntityGeometry;
import dev.lifus.janetreborn.service.placement.FluidAim;
import dev.lifus.janetreborn.service.placement.FluidPlan;
import dev.lifus.janetreborn.service.placement.OwnedFluids;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.placement.PlacementPlan;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.Notices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;

public final class AutoWaterModule extends Module {
  private static final int COORDINATOR_PRIORITY = 300;
  private static final int SOURCE_UPDATE_TIMEOUT_TICKS = 12;
  private static final int CLEANING_TIMEOUT_TICKS = 40;
  private static final int RECOVERY_TIMEOUT_TICKS = 100;
  private static final double MINIMUM_HORIZONTAL_OVERLAP = 0.01;
  private static final double MINIMUM_VERTICAL_OVERLAP = 0.1;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final Placement placements;
  private final FluidAim fluids;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final OwnedFluids ownedFluids;
  private final Notices notifications;
  private final BoolSetting extinguishFire = add(new BoolSetting("Extinguish Fire", true));
  private final BoolSetting autoFree = add(new BoolSetting("auto_free", "Escape Cobwebs", true));
  private final Set<BlockPos> trappedWebs = new HashSet<>();
  private Phase phase = Phase.SEARCHING;
  private AutoWaterIntent activeIntent = AutoWaterIntent.NONE;
  private BlockPos waterPosition;
  private long placedAt;
  private long tick;

  @Subscribe(priority = Priority.HIGHEST)
  private final Listener<TickEvent> clientTick = this::maintain;

  @Subscribe(priority = Priority.HIGHEST)
  private final Listener<PostInputEvent> postInputTick = this::act;

  public AutoWaterModule(
      Minecraft minecraft,
      Rotations rotations,
      Placement placements,
      FluidAim fluids,
      Hotbar hotbar,
      CombatGate coordinator,
      OwnedFluids ownedFluids,
      Notices notifications) {
    super(
        "AutoWater",
        "Extinguishes fire and optionally uses water to escape cobwebs",
        Category.PLAYER);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.placements = Objects.requireNonNull(placements, "placements");
    this.fluids = Objects.requireNonNull(fluids, "fluids");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.ownedFluids = Objects.requireNonNull(ownedFluids, "ownedFluids");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
  }

  @Override
  protected void onDisable() {
    stop();
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!connected()) stop();
    else if (!ready()) releaseControl();
  }

  private void act(PostInputEvent ignored) {
    if (!ready()) return;
    if (phase == Phase.CLEANING) {
      cleanUp();
      return;
    }
    if (phase == Phase.PICKUP) {
      pickup();
      return;
    }

    Set<BlockPos> currentWebs = autoFree.getValue() ? intersectingWebs() : Set.of();
    boolean burning =
        extinguishFire.getValue()
            && minecraft.player.isOnFire()
            && !minecraft.player.isInWaterOrRain();
    AutoWaterIntent intent =
        AutoWaterIntent.decide(burning, !currentWebs.isEmpty(), autoFree.getValue());
    if (intent == AutoWaterIntent.NONE) {
      releaseControl();
      return;
    }
    trappedWebs.addAll(currentWebs);
    int waterSlot = hotbar.find(Items.WATER_BUCKET);
    if (waterSlot < 0) {
      releaseControl();
      return;
    }

    Optional<PlacementPlan> solved = solveBestWaterPlacement(intent, currentWebs);
    if (solved.isEmpty()) {
      releaseControl();
      return;
    }
    PlacementPlan placement = solved.get();
    if (!ownedFluids.reserve(this, placement.target())) {
      releaseControl();
      return;
    }
    if (!rotations.request(this, placement.yaw(), placement.pitch(), COORDINATOR_PRIORITY, 2)) {
      abandonPlacement(placement.target());
      return;
    }
    if (!rotations.isInteractionReady(this)) {
      abandonPlacement(placement.target());
      return;
    }
    if (!placements.currentRotationHits(
        placement, rotations.getRequestedYaw(this), rotations.getRequestedPitch(this))) {
      abandonPlacement(placement.target());
      return;
    }
    if (!coordinator.acquire(this, CombatAction.AUTO_WATER_PLACE, COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)
        || !rotations.lockRequestedRotationForNextTick(this)) {
      abandonPlacement(placement.target());
      return;
    }

    var result =
        hotbar.withSlot(
            waterSlot,
            () ->
                rotations.runInteraction(
                    this,
                    placement.yaw(),
                    placement.pitch(),
                    () -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND)));
    rotations.clear(this);
    if (!result.consumesAction()) {
      abandonPlacement(placement.target());
      return;
    }
    waterPosition = placement.target().immutable();
    activeIntent = intent;
    placedAt = tick;
    phase = Phase.CLEANING;
    coordinator.release(this);
    notifications.hud("AutoWater", placementMessage(intent));
  }

  private Optional<PlacementPlan> solveBestWaterPlacement(
      AutoWaterIntent intent, Set<BlockPos> currentWebs) {
    LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
    if (intent.includesCobweb()) {
      for (BlockPos web : currentWebs) {
        for (int y = 0; y <= 2; y++) {
          for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
              candidates.add(web.offset(x, y, z).immutable());
            }
          }
        }
      }
    }
    if (intent.includesFire()) {
      candidates.addAll(
          EntityGeometry.occupied(
              minecraft.player, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP));
      BlockPos feet = minecraft.player.blockPosition();
      candidates.add(feet.immutable());
      for (int x = -1; x <= 1; x++) {
        for (int z = -1; z <= 1; z++) candidates.add(feet.offset(x, 0, z).immutable());
      }
    }

    Vec3 eye = minecraft.player.getEyePosition();
    List<BlockPos> ranked = new ArrayList<>(candidates);
    ranked.sort(
        Comparator.comparingDouble(
                (BlockPos candidate) -> placementBenefit(candidate, intent, currentWebs))
            .reversed()
            .thenComparingDouble(candidate -> eye.distanceToSqr(Vec3.atCenterOf(candidate))));
    return placements.solveOrdered(
        ranked,
        position ->
            placementBenefit(position, intent, currentWebs) > 0 && safeWaterPosition(position));
  }

  private double placementBenefit(
      BlockPos position, AutoWaterIntent intent, Set<BlockPos> currentWebs) {
    double benefit = intent.includesCobweb() ? waterCoverage(position, currentWebs) : 0;
    if (intent.includesFire()) {
      var source = new net.minecraft.world.phys.AABB(position).inflate(0.05);
      var bounds = minecraft.player.getBoundingBox();
      if (source.intersects(bounds)) benefit += 200;
      else if (source.intersects(bounds.move(minecraft.player.getDeltaMovement()))) benefit += 120;
      else if (position.getY() >= minecraft.player.blockPosition().getY()
          && position.distManhattan(minecraft.player.blockPosition()) <= 2) benefit += 30;
    }
    return benefit;
  }

  private boolean safeWaterPosition(BlockPos position) {
    return !ownedFluids.isReservedByOther(this, position)
        && fluids.canReachFutureSource(position)
        && !minecraft.level.getFluidState(position).is(FluidTags.WATER)
        && !minecraft.level.getFluidState(position).is(FluidTags.LAVA);
  }

  private static double waterCoverage(BlockPos source, Set<BlockPos> webs) {
    double score = 0;
    for (BlockPos web : webs) {
      if (source.getY() < web.getY()) continue;
      int horizontal = Math.abs(source.getX() - web.getX()) + Math.abs(source.getZ() - web.getZ());
      if (horizontal > 7) continue;
      int vertical = source.getY() - web.getY();
      if (horizontal == 0) score += 40 - Math.min(20, vertical * 2);
      else if (vertical == 0) score += 18 - horizontal * 2;
      else score += Math.max(1, 10 - horizontal - vertical);
    }
    return score;
  }

  private void cleanUp() {
    Set<BlockPos> currentWebs = autoFree.getValue() ? intersectingWebs() : Set.of();
    if (!autoFree.getValue()) trappedWebs.clear();
    else trappedWebs.addAll(currentWebs);
    trappedWebs.removeIf(position -> !minecraft.level.getBlockState(position).is(Blocks.COBWEB));
    boolean burning = minecraft.player.isOnFire() && !minecraft.player.isInWaterOrRain();
    AutoWaterIntent observed =
        AutoWaterIntent.decide(burning, !currentWebs.isEmpty(), autoFree.getValue());
    activeIntent = merge(activeIntent, observed);
    boolean stillBurning = activeIntent.includesFire() && burning;
    boolean stillTrapped = activeIntent.includesCobweb() && !trappedWebs.isEmpty();
    if (stillBurning || stillTrapped) {
      if (tick - placedAt >= CLEANING_TIMEOUT_TICKS) {
        phase = Phase.PICKUP;
        pickup();
        return;
      }
      releaseControl();
      return;
    }
    phase = Phase.PICKUP;
    pickup();
  }

  private void pickup() {
    if (waterPosition == null) {
      stop();
      return;
    }
    if (!ownedFluids.reserve(this, waterPosition)) {
      releaseControl();
      return;
    }
    var fluid = minecraft.level.getFluidState(waterPosition);
    boolean sourceVisible = fluid.is(FluidTags.WATER) && fluid.isSource();
    if (!sourceVisible) {
      releaseControl();
      if (tick - placedAt > SOURCE_UPDATE_TIMEOUT_TICKS) finish();
      return;
    }

    FluidPlan aim = fluids.solve(waterPosition, FluidTags.WATER);
    int bucketSlot = hotbar.find(Items.BUCKET);
    if (aim == null || bucketSlot < 0) {
      releaseControl();
      if (tick - placedAt > RECOVERY_TIMEOUT_TICKS) finish();
      return;
    }
    if (!rotations.request(this, aim.yaw(), aim.pitch(), COORDINATOR_PRIORITY, 2)) {
      coordinator.release(this);
      return;
    }
    if (!rotations.isInteractionReady(this)
        || !fluids.rayHits(
            aim,
            rotations.getRequestedYaw(this),
            rotations.getRequestedPitch(this),
            FluidTags.WATER)) {
      coordinator.release(this);
      return;
    }
    if (!coordinator.acquire(this, CombatAction.AUTO_WATER_PICKUP, COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)
        || !rotations.lockRequestedRotationForNextTick(this)) {
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
    if (result.consumesAction()) {
      notifications.hud("AutoWater", "Recovered the water");
      finish();
    } else {
      releaseControl();
    }
  }

  private Set<BlockPos> intersectingWebs() {
    Set<BlockPos> cells =
        EntityGeometry.occupied(
            minecraft.player, MINIMUM_HORIZONTAL_OVERLAP, MINIMUM_VERTICAL_OVERLAP);
    Set<BlockPos> result = new HashSet<>();
    for (BlockPos position : cells) {
      if (minecraft.level.getBlockState(position).is(Blocks.COBWEB)) {
        result.add(position.immutable());
      }
    }
    return result;
  }

  private boolean ready() {
    return connected()
        && minecraft.gui.screen() == null
        && minecraft.gui.overlay() == null
        && minecraft.mouseHandler.isMouseGrabbed()
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.player.isSleeping()
        && !minecraft.player.isMobilityRestricted()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.player.isPassenger()
        && !minecraft.player.isHandsBusy()
        && !minecraft.player.isUsingItem();
  }

  private boolean connected() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null;
  }

  private void releaseControl() {
    rotations.clear(this);
    coordinator.release(this);
  }

  private void abandonPlacement(BlockPos position) {
    ownedFluids.release(this, position);
    releaseControl();
  }

  private void finish() {
    trappedWebs.clear();
    activeIntent = AutoWaterIntent.NONE;
    waterPosition = null;
    phase = Phase.SEARCHING;
    releaseControl();
    ownedFluids.releaseAll(this);
  }

  private static AutoWaterIntent merge(AutoWaterIntent current, AutoWaterIntent observed) {
    boolean fire = current.includesFire() || observed.includesFire();
    boolean web = current.includesCobweb() || observed.includesCobweb();
    return AutoWaterIntent.decide(fire, web, true);
  }

  private static String placementMessage(AutoWaterIntent intent) {
    return switch (intent) {
      case FIRE -> "Placed water to extinguish you";
      case COBWEB -> "Placed water to clear the web";
      case FIRE_AND_COBWEB -> "Placed water for fire and cobweb escape";
      case NONE -> "Placed water";
    };
  }

  private void stop() {
    finish();
  }

  private enum Phase {
    SEARCHING,
    CLEANING,
    PICKUP
  }
}
