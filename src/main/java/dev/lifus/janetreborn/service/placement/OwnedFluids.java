package dev.lifus.janetreborn.service.placement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class OwnedFluids {
  private static final int UPDATE_TIMEOUT_TICKS = 20;
  private final Minecraft minecraft;
  private final Set<BlockPos> ownedSources = new HashSet<>();
  private final List<PendingPlacement> pending = new ArrayList<>();
  private final FluidReservations reservations = new FluidReservations();
  private ClientLevel trackedLevel;
  private PlacementAttempt currentAttempt;
  private long tick;

  public OwnedFluids(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public void beginUse(Player player, InteractionHand hand) {
    synchronizeLevel();
    currentAttempt = null;
    if (minecraft.level == null) return;
    TagKey<Fluid> fluidTag;
    var stack = player.getItemInHand(hand);
    if (stack.is(Items.WATER_BUCKET)) fluidTag = FluidTags.WATER;
    else if (stack.is(Items.LAVA_BUCKET)) fluidTag = FluidTags.LAVA;
    else return;

    Vec3 eye = player.getEyePosition();
    Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
    var hit =
        minecraft.level.clip(
            new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    if (hit.getType() != HitResult.Type.BLOCK) return;

    Set<BlockPos> candidates =
        Set.of(
            hit.getBlockPos().immutable(),
            hit.getBlockPos().relative(hit.getDirection()).immutable());
    Set<BlockPos> occupiedBefore = new HashSet<>();
    for (BlockPos candidate : candidates) {
      if (isSource(candidate, fluidTag)) occupiedBefore.add(candidate);
    }
    currentAttempt = new PlacementAttempt(fluidTag, candidates, Set.copyOf(occupiedBefore));
  }

  public void finishUse(InteractionResult result) {
    PlacementAttempt attempt = currentAttempt;
    currentAttempt = null;
    if (attempt == null || result == null || !result.consumesAction()) return;
    PendingPlacement placement = new PendingPlacement(attempt, tick + UPDATE_TIMEOUT_TICKS);
    if (!detect(placement)) pending.add(placement);
  }

  public void tick() {
    tick++;
    synchronizeLevel();
    if (minecraft.level == null) {
      return;
    }

    ownedSources.removeIf(
        position -> {
          var fluid = minecraft.level.getFluidState(position);
          return !fluid.isSource() || !fluid.is(FluidTags.WATER) && !fluid.is(FluidTags.LAVA);
        });
    for (Iterator<PendingPlacement> iterator = pending.iterator(); iterator.hasNext(); ) {
      PendingPlacement placement = iterator.next();
      if (detect(placement) || tick > placement.expiresAt()) iterator.remove();
    }
  }

  public boolean isMine(BlockPos position) {
    return ownedSources.contains(position);
  }

  public boolean reserve(Object owner, BlockPos position) {
    synchronizeLevel();
    return reservations.acquire(owner, position);
  }

  public boolean isReservedByOther(Object owner, BlockPos position) {
    synchronizeLevel();
    return reservations.isOwnedByOther(owner, position);
  }

  public boolean isUnavailable(Object owner, BlockPos position) {
    return isMine(position) || isReservedByOther(owner, position);
  }

  public void release(Object owner, BlockPos position) {
    reservations.release(owner, position);
  }

  public void releaseAll(Object owner) {
    reservations.releaseAll(owner);
  }

  private boolean detect(PendingPlacement placement) {
    PlacementAttempt attempt = placement.attempt();
    for (BlockPos candidate : attempt.candidates()) {
      if (!attempt.occupiedBefore().contains(candidate)
          && isSource(candidate, attempt.fluidTag())) {
        ownedSources.add(candidate);
        return true;
      }
    }
    return false;
  }

  private boolean isSource(BlockPos position, TagKey<Fluid> fluidTag) {
    if (minecraft.level == null) return false;
    var fluid = minecraft.level.getFluidState(position);
    return fluid.isSource() && fluid.is(fluidTag);
  }

  private void synchronizeLevel() {
    if (trackedLevel == minecraft.level) return;
    trackedLevel = minecraft.level;
    ownedSources.clear();
    pending.clear();
    currentAttempt = null;
    reservations.clear();
  }

  private record PlacementAttempt(
      TagKey<Fluid> fluidTag, Set<BlockPos> candidates, Set<BlockPos> occupiedBefore) {}

  private record PendingPlacement(PlacementAttempt attempt, long expiresAt) {}
}
