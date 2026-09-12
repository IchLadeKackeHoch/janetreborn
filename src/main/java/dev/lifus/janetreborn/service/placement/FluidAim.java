package dev.lifus.janetreborn.service.placement;

import dev.lifus.janetreborn.service.rotation.Rotations;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FluidAim {
  private static final double[] HORIZONTAL_SAMPLES = {0.0, -0.25, 0.25};
  private static final double[] VERTICAL_SAMPLES = {0.0, -0.3, 0.3};
  private static final double HIT_ALIGNMENT_TOLERANCE_SQUARED = 0.04 * 0.04;
  private final Minecraft minecraft;
  private final Rotations rotations;

  public FluidAim(Minecraft minecraft, Rotations rotations) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.rotations = Objects.requireNonNull(rotations, "rotations");
  }

  public FluidPlan solve(BlockPos target) {
    return solve(target, FluidTags.LAVA);
  }

  public FluidPlan solve(BlockPos target, TagKey<Fluid> fluidTag) {
    Objects.requireNonNull(fluidTag, "fluidTag");
    if (!isSource(target, fluidTag) || minecraft.player == null) return null;
    Vec3 eye = minecraft.player.getEyePosition();
    FluidPlan best = null;
    double bestCost = Double.MAX_VALUE;
    int sampleIndex = 0;
    for (Vec3 baseSample : samples(target)) {
      long seed = target.asLong() ^ rotations.getVariationEpoch() ^ sampleIndex++;
      double variation = rotations.getHitVectorVariation();
      Vec3 sample =
          baseSample.add(
              HitJitter.offset(seed, 0, variation),
              HitJitter.offset(seed, 1, variation),
              HitJitter.offset(seed, 2, variation));
      if (eye.distanceToSqr(sample) > square(minecraft.player.blockInteractionRange())) continue;
      float[] aim = rotationsTo(eye, sample);
      BlockHitResult trace = rayTrace(aim[0], aim[1]);
      if (trace.getType() != HitResult.Type.BLOCK || !trace.getBlockPos().equals(target)) continue;
      aim = rotationsTo(eye, trace.getLocation());
      double cost =
          Math.abs(wrapDegrees(aim[0] - minecraft.player.getYRot()))
              + Math.abs(aim[1] - minecraft.player.getXRot()) * 0.5;
      if (cost < bestCost) {
        bestCost = cost;
        best = new FluidPlan(target.immutable(), trace.getLocation(), aim[0], aim[1]);
      }
    }
    return best;
  }

  public boolean canReachFutureSource(BlockPos target) {
    if (minecraft.player == null || minecraft.level == null) return false;
    Vec3 eye = minecraft.player.getEyePosition();
    double safeReach = minecraft.player.blockInteractionRange();
    for (Vec3 sample : samples(target)) {
      if (eye.distanceToSqr(sample) > square(safeReach)) continue;
      BlockHitResult trace =
          minecraft.level.clip(
              new ClipContext(
                  eye,
                  sample,
                  ClipContext.Block.OUTLINE,
                  ClipContext.Fluid.NONE,
                  minecraft.player));
      if (trace.getType() == HitResult.Type.MISS || trace.getBlockPos().equals(target)) return true;
    }
    return false;
  }

  public boolean rayHits(FluidPlan candidate, float yaw, float pitch) {
    return rayHits(candidate, yaw, pitch, FluidTags.LAVA);
  }

  public boolean rayHits(FluidPlan candidate, float yaw, float pitch, TagKey<Fluid> fluidTag) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(fluidTag, "fluidTag");
    if (!isSource(candidate.target(), fluidTag)) return false;
    BlockHitResult trace = rayTrace(yaw, pitch);
    return trace.getType() == HitResult.Type.BLOCK
        && trace.getBlockPos().equals(candidate.target())
        && trace.getLocation().distanceToSqr(candidate.hitVector())
            <= HIT_ALIGNMENT_TOLERANCE_SQUARED;
  }

  private BlockHitResult rayTrace(float yaw, float pitch) {
    Vec3 eye = minecraft.player.getEyePosition();
    Vec3 end =
        eye.add(
            Vec3.directionFromRotation(pitch, yaw).scale(minecraft.player.blockInteractionRange()));
    return minecraft.level.clip(
        new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, minecraft.player));
  }

  private boolean isSource(BlockPos target, TagKey<Fluid> fluidTag) {
    if (minecraft.level == null) return false;
    var fluid = minecraft.level.getFluidState(target);
    return fluid.is(fluidTag) && fluid.isSource();
  }

  private static float[] rotationsTo(Vec3 from, Vec3 to) {
    Vec3 difference = to.subtract(from);
    return new float[] {
      (float) Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0f,
      (float) -Math.toDegrees(Math.atan2(difference.y, Math.hypot(difference.x, difference.z)))
    };
  }

  private static double square(double value) {
    return value * value;
  }

  private static Vec3[] samples(BlockPos target) {
    Vec3[] samples =
        new Vec3[HORIZONTAL_SAMPLES.length * HORIZONTAL_SAMPLES.length * VERTICAL_SAMPLES.length];
    Vec3 center = Vec3.atCenterOf(target);
    int index = 0;
    for (double x : HORIZONTAL_SAMPLES) {
      for (double z : HORIZONTAL_SAMPLES) {
        for (double y : VERTICAL_SAMPLES) samples[index++] = center.add(x, y, z);
      }
    }
    return samples;
  }

  private static float wrapDegrees(float value) {
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }
}
