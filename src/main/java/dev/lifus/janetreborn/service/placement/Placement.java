package dev.lifus.janetreborn.service.placement;

import dev.lifus.janetreborn.service.rotation.Rotations;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class Placement {
  private static final Direction[] SUPPORT_ORDER = {
    Direction.DOWN, Direction.NORTH, Direction.SOUTH,
    Direction.WEST, Direction.EAST, Direction.UP
  };
  private static final double[] FACE_SAMPLES = {0.0, -0.15, 0.15, -0.30, 0.30, -0.45, 0.45};
  private static final double FACE_LIMIT = 0.44;
  private static final double HIT_ALIGNMENT_TOLERANCE_SQUARED = 0.03 * 0.03;
  private final Minecraft minecraft;
  private final Rotations rotations;

  public Placement(Minecraft minecraft, Rotations rotations) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
  }

  public Optional<PlacementPlan> solve(
      Collection<BlockPos> targets, Predicate<BlockPos> allowedTarget) {
    if (minecraft.player == null || minecraft.level == null) return Optional.empty();
    PlacementPlan best = null;
    for (BlockPos target : targets) {
      if (!allowedTarget.test(target) || !canReplace(target)) continue;
      PlacementPlan candidate = solve(target);
      if (candidate != null && (best == null || candidate.score() < best.score())) best = candidate;
    }
    return Optional.ofNullable(best);
  }

  public Optional<PlacementPlan> solveOrdered(
      Collection<BlockPos> targets, Predicate<BlockPos> allowedTarget) {
    if (minecraft.player == null || minecraft.level == null) return Optional.empty();
    for (BlockPos target : targets) {
      if (!allowedTarget.test(target) || !canReplace(target)) continue;
      PlacementPlan candidate = solve(target);
      if (candidate != null) return Optional.of(candidate);
    }
    return Optional.empty();
  }

  public PlacementPlan refresh(PlacementPlan candidate) {
    if (minecraft.player == null || minecraft.level == null || !canReplace(candidate.target()))
      return null;
    if (!isHitValid(candidate.target(), candidate.hit())) return solve(candidate.target());
    float[] aim = rotationsTo(minecraft.player.getEyePosition(), candidate.hit().getLocation());
    return new PlacementPlan(
        candidate.target(), candidate.hit(), aim[0], aim[1], rotationCost(aim));
  }

  public boolean isValid(PlacementPlan candidate) {
    return minecraft.player != null
        && minecraft.level != null
        && canReplace(candidate.target())
        && isHitValid(candidate.target(), candidate.hit());
  }

  public boolean currentRotationHits(PlacementPlan candidate, float yaw, float pitch) {
    if (minecraft.player == null
        || minecraft.level == null
        || !isSupportValid(candidate.target(), candidate.hit())) {
      return false;
    }
    Vec3 eye = minecraft.player.getEyePosition();
    Vec3 end =
        eye.add(
            Vec3.directionFromRotation(pitch, yaw).scale(minecraft.player.blockInteractionRange()));
    BlockHitResult trace =
        minecraft.level.clip(
            new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    return trace.getType() == HitResult.Type.BLOCK
        && trace.getBlockPos().equals(candidate.hit().getBlockPos())
        && trace.getDirection() == candidate.hit().getDirection()
        && trace.getLocation().distanceToSqr(candidate.hit().getLocation())
            <= HIT_ALIGNMENT_TOLERANCE_SQUARED;
  }

  public boolean rotationHitsSupport(PlacementPlan candidate, float yaw, float pitch) {
    if (minecraft.player == null
        || minecraft.level == null
        || !isSupportValid(candidate.target(), candidate.hit())) {
      return false;
    }
    Vec3 eye = minecraft.player.getEyePosition();
    Vec3 end =
        eye.add(
            Vec3.directionFromRotation(pitch, yaw).scale(minecraft.player.blockInteractionRange()));
    BlockHitResult trace =
        minecraft.level.clip(
            new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    return trace.getType() == HitResult.Type.BLOCK
        && trace.getBlockPos().equals(candidate.hit().getBlockPos())
        && trace.getDirection() == candidate.hit().getDirection();
  }

  public boolean canReplace(BlockPos target) {
    if (minecraft.level == null || !minecraft.level.isInWorldBounds(target)) return false;
    var state = minecraft.level.getBlockState(target);
    return state.canBeReplaced() && !state.liquid();
  }

  private PlacementPlan solve(BlockPos target) {
    Vec3 eye = minecraft.player.getEyePosition();
    double reachSquared =
        minecraft.player.blockInteractionRange() * minecraft.player.blockInteractionRange();
    PlacementPlan best = null;
    for (Direction offset : SUPPORT_ORDER) {
      BlockPos support = target.relative(offset);
      if (!isSolidSupport(support)) continue;
      Direction face = offset.getOpposite();
      Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
      Vec3 center = Vec3.atCenterOf(support).add(normal.scale(0.5));
      for (int firstIndex = 0; firstIndex < FACE_SAMPLES.length; firstIndex++) {
        for (int secondIndex = 0; secondIndex < FACE_SAMPLES.length; secondIndex++) {
          long seed =
              target.asLong()
                  ^ Long.rotateLeft(support.asLong(), 17)
                  ^ ((long) face.ordinal() << 48)
                  ^ rotations.getVariationEpoch()
                  ^ ((long) firstIndex << 8)
                  ^ secondIndex;
          double first =
              boundedFaceCoordinate(
                  FACE_SAMPLES[firstIndex]
                      + HitJitter.offset(seed, 0, rotations.getHitVectorVariation()));
          double second =
              boundedFaceCoordinate(
                  FACE_SAMPLES[secondIndex]
                      + HitJitter.offset(seed, 1, rotations.getHitVectorVariation()));
          Vec3 sample =
              switch (face.getAxis()) {
                case X -> center.add(0.0, first, second);
                case Y -> center.add(first, 0.0, second);
                case Z -> center.add(first, second, 0.0);
              };
          BlockHitResult trace = traceToFace(eye, sample, normal);
          if (trace == null
              || !trace.getBlockPos().equals(support)
              || trace.getDirection() != face
              || eye.distanceToSqr(trace.getLocation()) > reachSquared) continue;
          float[] aim = rotationsTo(eye, trace.getLocation());
          double faceCenterPenalty = (Math.abs(first) + Math.abs(second)) * 3.0;
          double score =
              rotationCost(aim) + eye.distanceTo(trace.getLocation()) * 0.05 + faceCenterPenalty;
          if (best == null || score < best.score()) {
            best = new PlacementPlan(target.immutable(), trace, aim[0], aim[1], score);
          }
        }
      }
    }
    return best;
  }

  private BlockHitResult traceToFace(Vec3 eye, Vec3 sample, Vec3 normal) {
    BlockHitResult trace =
        minecraft.level.clip(
            new ClipContext(
                eye,
                sample.subtract(normal.scale(0.01)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                minecraft.player));
    return trace.getType() == HitResult.Type.BLOCK ? trace : null;
  }

  private boolean isHitValid(BlockPos target, BlockHitResult hit) {
    if (!isSupportValid(target, hit)) return false;
    Vec3 normal =
        new Vec3(
            hit.getDirection().getStepX(),
            hit.getDirection().getStepY(),
            hit.getDirection().getStepZ());
    BlockHitResult trace =
        traceToFace(minecraft.player.getEyePosition(), hit.getLocation(), normal);
    return trace != null
        && trace.getBlockPos().equals(hit.getBlockPos())
        && trace.getDirection() == hit.getDirection();
  }

  private boolean isSupportValid(BlockPos target, BlockHitResult hit) {
    return isSolidSupport(hit.getBlockPos())
        && hit.getBlockPos().relative(hit.getDirection()).equals(target);
  }

  private boolean isSolidSupport(BlockPos position) {
    var state = minecraft.level.getBlockState(position);
    return !state.isAir() && !state.liquid() && !state.canBeReplaced();
  }

  private double rotationCost(float[] aim) {
    float referenceYaw =
        rotations.isArtificialRotationActive()
            ? rotations.getServerYaw()
            : minecraft.player.getYRot();
    float referencePitch =
        rotations.isArtificialRotationActive()
            ? rotations.getServerPitch()
            : minecraft.player.getXRot();
    return Math.abs(wrapDegrees(aim[0] - referenceYaw)) + Math.abs(aim[1] - referencePitch) * 0.5;
  }

  private static float[] rotationsTo(Vec3 from, Vec3 to) {
    Vec3 difference = to.subtract(from);
    double horizontal = Math.hypot(difference.x, difference.z);
    return new float[] {
      (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0f,
      (float) -Math.toDegrees(Math.atan2(difference.y, horizontal))
    };
  }

  private static double boundedFaceCoordinate(double coordinate) {
    return Math.max(-FACE_LIMIT, Math.min(FACE_LIMIT, coordinate));
  }

  private static float wrapDegrees(float value) {
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }
}
