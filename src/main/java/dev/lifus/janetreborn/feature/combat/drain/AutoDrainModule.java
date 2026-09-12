package dev.lifus.janetreborn.feature.combat.drain;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import dev.lifus.janetreborn.service.placement.FluidAim;
import dev.lifus.janetreborn.service.placement.FluidPlan;
import dev.lifus.janetreborn.service.placement.OwnedFluids;
import dev.lifus.janetreborn.service.rotation.Rotations;
import dev.lifus.janetreborn.ui.Notices;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class AutoDrainModule extends Module {
  private static final int COORDINATOR_PRIORITY = 70;
  private final Minecraft minecraft;
  private final Rotations rotations;
  private final FluidAim fluids;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final OwnedFluids ownFluids;
  private final FriendStore friends;
  private final Notices notifications;
  private final BoolSetting water = add(new BoolSetting("Water", true));
  private final BoolSetting lava = add(new BoolSetting("Lava", false));
  private final RangeSetting<Integer> actionDelay =
      add(
          new RangeSetting<>("Action Delay", new Range<>(0, 0), 0, 30, 1)
              .legacyCenterVariation("delay", "randomization")
              .unit("ticks"));
  private long tick;
  private long nextActionTick;

  @Subscribe private final Listener<TickEvent> clientTick = this::maintain;
  @Subscribe private final Listener<PostInputEvent> postInputTick = this::act;

  public AutoDrainModule(
      Minecraft minecraft,
      Rotations rotations,
      FluidAim fluids,
      Hotbar hotbar,
      CombatGate coordinator,
      OwnedFluids ownFluids,
      FriendStore friends,
      Notices notifications) {
    super(
        "AutoDrain",
        "Collects nearby water and lava sources with empty hotbar buckets",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
    this.fluids = Objects.requireNonNull(fluids, "fluids");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.ownFluids = Objects.requireNonNull(ownFluids, "ownFluids");
    this.friends = Objects.requireNonNull(friends, "friends");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
  }

  @Override
  protected void onEnable() {
    nextActionTick = 0;
  }

  @Override
  protected void onDisable() {
    nextActionTick = 0;
    stop();
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!ready()) stop();
  }

  private void act(PostInputEvent ignored) {
    int bucketSlot = hotbar.find(Items.BUCKET);
    if (!ready() || bucketSlot < 0 || !water.getValue() && !lava.getValue()) {
      stop();
      return;
    }
    if (tick < nextActionTick) {
      releaseControl();
      return;
    }

    Target target = nearestSource(friendProtectedCells());
    if (target == null) {
      stop();
      return;
    }
    if (!ownFluids.reserve(this, target.aim().target())) {
      releaseControl();
      return;
    }

    FluidPlan aim = target.aim();
    if (!rotations.request(this, aim.yaw(), aim.pitch(), COORDINATOR_PRIORITY, 2)) {
      releaseControl();
      return;
    }
    if (!rotations.isInteractionReady(this)) {
      releaseControl();
      return;
    }
    if (!fluids.rayHits(
        aim,
        rotations.getRequestedYaw(this),
        rotations.getRequestedPitch(this),
        target.fluidTag())) {
      releaseControl();
      return;
    }
    if (friendOccupies(target.aim().target())) {
      stop();
      return;
    }
    if (!coordinator.acquire(this, CombatAction.DRAIN, COORDINATOR_PRIORITY, 2)
        || !coordinator.markAction(this)) {
      releaseControl();
      return;
    }
    if (!rotations.lockRequestedRotationForNextTick(this)) {
      releaseControl();
      return;
    }

    var result =
        hotbar.withSlot(
            bucketSlot,
            () -> {
              if (friendOccupies(aim.target())) return InteractionResult.PASS;
              return rotations.runInteraction(
                  this,
                  aim.yaw(),
                  aim.pitch(),
                  () -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
            });
    if (result.consumesAction()) {
      nextActionTick = tick + 1 + actionDelay();
      String fluid = target.fluidTag() == FluidTags.LAVA ? "lava" : "water";
      notifications.hud("AutoDrain", "Drained " + fluid);
    }
    releaseControl();
  }

  private Target nearestSource(Set<BlockPos> protectedCells) {
    double reach = minecraft.player.blockInteractionRange();
    int radius = (int) Math.ceil(reach);
    BlockPos center = minecraft.player.blockPosition();
    Vec3 eye = minecraft.player.getEyePosition();
    List<Source> sources = new ArrayList<>();

    for (BlockPos cursor :
        BlockPos.betweenClosed(
            center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
      if (protectedCells.contains(cursor)) continue;
      TagKey<Fluid> fluidTag = enabledFluidAt(cursor);
      if (fluidTag == null || ownFluids.isUnavailable(this, cursor)) continue;
      double distance = eye.distanceToSqr(Vec3.atCenterOf(cursor));
      sources.add(new Source(cursor.immutable(), fluidTag, distance));
    }
    sources.sort(Comparator.comparingDouble(Source::distance));
    for (Source source : sources) {
      FluidPlan aim = fluids.solve(source.position(), source.fluidTag());
      if (aim != null) return new Target(aim, source.fluidTag());
    }
    return null;
  }

  private Set<BlockPos> friendProtectedCells() {
    Set<BlockPos> protectedCells = new HashSet<>();
    for (var player : minecraft.level.players()) {
      if (player == minecraft.player || player.isRemoved() || !friends.isFriend(player.getUUID()))
        continue;
      addIntersectingCells(protectedCells, player.getBoundingBox());
      addIntersectingCells(protectedCells, player.getBoundingBox().move(player.getDeltaMovement()));
    }
    return protectedCells;
  }

  private boolean friendOccupies(BlockPos position) {
    AABB source = new AABB(position);
    for (var player : minecraft.level.players()) {
      if (player == minecraft.player || player.isRemoved() || !friends.isFriend(player.getUUID()))
        continue;
      AABB bounds = player.getBoundingBox();
      if (source.intersects(bounds) || source.intersects(bounds.move(player.getDeltaMovement())))
        return true;
    }
    return false;
  }

  private static void addIntersectingCells(Set<BlockPos> cells, AABB bounds) {
    int minX = (int) Math.floor(bounds.minX + 1.0E-4);
    int maxX = (int) Math.floor(bounds.maxX - 1.0E-4);
    int minY = (int) Math.floor(bounds.minY + 1.0E-4);
    int maxY = (int) Math.floor(bounds.maxY - 1.0E-4);
    int minZ = (int) Math.floor(bounds.minZ + 1.0E-4);
    int maxZ = (int) Math.floor(bounds.maxZ - 1.0E-4);
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) cells.add(new BlockPos(x, y, z));
      }
    }
  }

  private TagKey<Fluid> enabledFluidAt(BlockPos position) {
    var state = minecraft.level.getFluidState(position);
    if (!state.isSource()) return null;
    if (water.getValue() && state.is(FluidTags.WATER)) return FluidTags.WATER;
    if (lava.getValue() && state.is(FluidTags.LAVA)) return FluidTags.LAVA;
    return null;
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

  private int actionDelay() {
    int minimum = actionDelay.getValue().minimum();
    int maximum = actionDelay.getValue().maximum();
    return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
  }

  private void releaseControl() {
    rotations.clear(this);
    coordinator.release(this);
    ownFluids.releaseAll(this);
  }

  private void stop() {
    releaseControl();
  }

  private record Target(FluidPlan aim, TagKey<Fluid> fluidTag) {}

  private record Source(BlockPos position, TagKey<Fluid> fluidTag, double distance) {}
}
