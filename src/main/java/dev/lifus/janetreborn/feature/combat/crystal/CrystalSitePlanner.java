package dev.lifus.janetreborn.feature.combat.crystal;

import dev.lifus.janetreborn.service.combat.ExplosionDamage;
import dev.lifus.janetreborn.service.placement.Placement;
import dev.lifus.janetreborn.service.placement.PlacementPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CrystalSitePlanner {
  private static final int[][] HORIZONTAL_OFFSETS = {
    {0, 0}, {0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}
  };
  private static final double CRYSTAL_POSITION_TOLERANCE_SQUARED = 0.35 * 0.35;
  private final Minecraft minecraft;
  private final Placement placements;

  CrystalSitePlanner(Minecraft minecraft, Placement placements) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.placements = Objects.requireNonNull(placements, "placements");
  }

  Optional<CrystalSite> select(
      Player target,
      Vec3 recordedHit,
      boolean mayPlaceObsidian,
      double minimumSelfDistance,
      double maximumSelfDamage) {
    if (minecraft.player == null || minecraft.level == null) return Optional.empty();
    BlockPos feet =
        BlockPos.containing(recordedHit.x, target.getBoundingBox().minY + 1.0E-3, recordedHit.z);
    List<BlockPos> nearby = nearby(feet, recordedHit);

    for (BlockPos candidate : nearby) {
      if (isCrystalBase(candidate)
          && canUseCrystalBase(candidate, minimumSelfDistance, maximumSelfDamage)) {
        return Optional.of(new CrystalSite(candidate, null));
      }
    }
    if (!mayPlaceObsidian) return Optional.empty();

    List<BlockPos> newBases =
        nearby.stream()
            .filter(position -> position.getY() == feet.getY())
            .filter(position -> !new AABB(position).intersects(target.getBoundingBox()))
            .toList();
    Optional<PlacementPlan> placement =
        placements.solve(
            newBases,
            position ->
                farEnoughFromSelf(position, minimumSelfDistance)
                    && safeSelfDamage(position, maximumSelfDamage)
                    && crystalSpaceIsEmpty(position)
                    && crystalSpaceHasNoEntities(position)
                    && blockSpaceHasNoEntities(position));
    return placement.map(plan -> new CrystalSite(plan.target(), plan));
  }

  PlacementPlan refreshBasePlacement(CrystalSite site, double minimumSelfDistance) {
    return site.needsBase()
            && farEnoughFromSelf(site.base(), minimumSelfDistance)
            && crystalSpaceIsEmpty(site.base())
            && crystalSpaceHasNoEntities(site.base())
            && blockSpaceHasNoEntities(site.base())
        ? placements.refresh(site.basePlacement())
        : null;
  }

  PlacementPlan crystalPlacement(BlockPos base, double minimumSelfDistance) {
    return crystalPlacement(base, minimumSelfDistance, Double.POSITIVE_INFINITY);
  }

  PlacementPlan crystalPlacement(
      BlockPos base, double minimumSelfDistance, double maximumSelfDamage) {
    if (!canUseCrystalBase(base, minimumSelfDistance, maximumSelfDamage)) return null;
    Vec3 hitLocation = Vec3.atCenterOf(base).add(0.0, 0.5, 0.0);
    float[] aim = rotationsTo(minecraft.player.getEyePosition(), hitLocation);
    PlacementPlan plan =
        new PlacementPlan(
            base.above().immutable(),
            new BlockHitResult(hitLocation, Direction.UP, base.immutable(), false),
            aim[0],
            aim[1],
            rotationCost(aim));
    return placements.currentRotationHits(plan, plan.yaw(), plan.pitch()) ? plan : null;
  }

  boolean baseConfirmed(CrystalSite site) {
    return isCrystalBase(site.base());
  }

  Set<Integer> currentCrystalIds() {
    if (minecraft.level == null) return Set.of();
    Set<Integer> ids = new LinkedHashSet<>();
    for (Entity entity : minecraft.level.entitiesForRendering()) {
      if (entity instanceof EndCrystal) ids.add(entity.getId());
    }
    return Set.copyOf(ids);
  }

  EndCrystal findSequenceCrystal(BlockPos base, Set<Integer> excludedIds) {
    if (minecraft.level == null) return null;
    for (Entity entity : minecraft.level.entitiesForRendering()) {
      if (entity instanceof EndCrystal crystal
          && crystal.isAlive()
          && !crystal.isRemoved()
          && isMatchingCrystal(crystal.getId(), excludedIds, base, crystal.position())) {
        return crystal;
      }
    }
    return null;
  }

  boolean rotationHits(EndCrystal crystal, float yaw, float pitch) {
    if (minecraft.player == null || minecraft.level == null) return false;
    Vec3 eye = minecraft.player.getEyePosition();
    double reach = minecraft.player.entityInteractionRange();
    Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
    Vec3 end = eye.add(direction.scale(reach));
    BlockHitResult blockHit =
        minecraft.level.clip(
            new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    double maximumDistanceSquared = reach * reach;
    if (blockHit.getType() == HitResult.Type.BLOCK) {
      maximumDistanceSquared = eye.distanceToSqr(blockHit.getLocation());
    }
    AABB search =
        minecraft.player.getBoundingBox().expandTowards(direction.scale(reach)).inflate(1.0);
    EntityHitResult entityHit =
        ProjectileUtil.getEntityHitResult(
            minecraft.player, eye, end, search, Entity::isPickable, maximumDistanceSquared);
    return entityHit != null && entityHit.getEntity() == crystal;
  }

  float[] aimAt(EndCrystal crystal) {
    return rotationsTo(minecraft.player.getEyePosition(), crystal.getBoundingBox().getCenter());
  }

  static boolean isMatchingCrystal(
      int entityId, Set<Integer> excludedIds, BlockPos base, Vec3 crystalPosition) {
    if (excludedIds.contains(entityId)) return false;
    Vec3 expected = Vec3.atBottomCenterOf(base.above());
    double horizontalX = crystalPosition.x - expected.x;
    double horizontalZ = crystalPosition.z - expected.z;
    return horizontalX * horizontalX + horizontalZ * horizontalZ
            <= CRYSTAL_POSITION_TOLERANCE_SQUARED
        && crystalPosition.y >= base.getY() + 0.75
        && crystalPosition.y <= base.getY() + 2.25;
  }

  private List<BlockPos> nearby(BlockPos feet, Vec3 recordedHit) {
    Set<BlockPos> candidates = new LinkedHashSet<>();
    for (int[] offset : HORIZONTAL_OFFSETS) {
      candidates.add(feet.offset(offset[0], -1, offset[1]).immutable());
      candidates.add(feet.offset(offset[0], 0, offset[1]).immutable());
    }
    List<BlockPos> ordered = new ArrayList<>(candidates);
    ordered.sort(
        Comparator.comparingDouble(
            position -> Vec3.atCenterOf(position).distanceToSqr(recordedHit)));
    return ordered;
  }

  private boolean canUseCrystalBase(
      BlockPos base, double minimumSelfDistance, double maximumSelfDamage) {
    return isCrystalBase(base)
        && farEnoughFromSelf(base, minimumSelfDistance)
        && safeSelfDamage(base, maximumSelfDamage)
        && crystalSpaceIsEmpty(base)
        && crystalSpaceHasNoEntities(base)
        && minecraft.player.isWithinBlockInteractionRange(base, 0.0);
  }

  private boolean blockSpaceHasNoEntities(BlockPos position) {
    return minecraft
        .level
        .getEntities(
            (Entity) null, new AABB(position), entity -> entity.isAlive() && !entity.isRemoved())
        .isEmpty();
  }

  private boolean crystalSpaceHasNoEntities(BlockPos base) {
    return minecraft
        .level
        .getEntities(
            (Entity) null,
            new AABB(base.above()).expandTowards(0.0, 1.0, 0.0),
            entity -> entity.isAlive() && !entity.isRemoved())
        .isEmpty();
  }

  private boolean crystalSpaceIsEmpty(BlockPos base) {
    return minecraft.level.isEmptyBlock(base.above())
        && minecraft.level.isEmptyBlock(base.above(2));
  }

  private boolean isCrystalBase(BlockPos base) {
    if (minecraft.level == null || !minecraft.level.isInWorldBounds(base)) return false;
    var state = minecraft.level.getBlockState(base);
    return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
  }

  private boolean farEnoughFromSelf(BlockPos base, double minimumSelfDistance) {
    Vec3 crystalCenter = Vec3.atBottomCenterOf(base.above());
    return minecraft.player.position().distanceToSqr(crystalCenter)
        >= minimumSelfDistance * minimumSelfDistance;
  }

  private boolean safeSelfDamage(BlockPos base, double maximumSelfDamage) {
    return ExplosionDamage.estimateSelf(minecraft, Vec3.atBottomCenterOf(base.above()), null)
        <= maximumSelfDamage;
  }

  private double rotationCost(float[] aim) {
    float yaw = minecraft.player.getYRot();
    float pitch = minecraft.player.getXRot();
    return Math.abs(wrapDegrees(aim[0] - yaw)) + Math.abs(aim[1] - pitch) * 0.5;
  }

  private static float[] rotationsTo(Vec3 from, Vec3 to) {
    Vec3 difference = to.subtract(from);
    double horizontal = Math.hypot(difference.x, difference.z);
    return new float[] {
      (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0f,
      (float) -Math.toDegrees(Math.atan2(difference.y, horizontal))
    };
  }

  private static float wrapDegrees(float value) {
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }
}
