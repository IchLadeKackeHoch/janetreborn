package dev.lifus.janetreborn.feature.combat.aim;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class VisibleHitPointSelector {
  private static final List<Sample> SAMPLES =
      List.of(
          new Sample(0.50, 0.64, 0.50, 0.00),
          new Sample(0.50, 0.76, 0.50, 0.15),
          new Sample(0.50, 0.50, 0.50, 0.25),
          new Sample(0.32, 0.64, 0.50, 0.35),
          new Sample(0.68, 0.64, 0.50, 0.35),
          new Sample(0.50, 0.64, 0.32, 0.35),
          new Sample(0.50, 0.64, 0.68, 0.35));
  private static final float PREFERRED_POINT_TOLERANCE_DEGREES = 1.5f;
  private final Minecraft minecraft;

  public VisibleHitPointSelector(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public HitPoint select(
      LivingEntity entity, Vec3 eye, float cameraYaw, float cameraPitch, HitPoint preferred) {
    HitPoint best = null;
    double bestCost = Double.POSITIVE_INFINITY;
    for (Sample sample : SAMPLES) {
      HitPoint candidate = point(entity, sample);
      if (!isVisible(eye, candidate.position())) continue;
      AimRotation rotation = rotationsTo(eye, candidate.position());
      double cost = AimMath.angularDistance(cameraYaw, cameraPitch, rotation) + sample.penalty();
      if (cost < bestCost) {
        bestCost = cost;
        best = candidate;
      }
    }
    if (best == null || preferred == null) return best;

    HitPoint retained = point(entity, preferred.sample());
    if (!isVisible(eye, retained.position())) return best;
    double retainedCost =
        AimMath.angularDistance(cameraYaw, cameraPitch, rotationsTo(eye, retained.position()))
            + retained.sample().penalty();
    return retainedCost <= bestCost + PREFERRED_POINT_TOLERANCE_DEGREES ? retained : best;
  }

  public boolean isVisible(Vec3 eye, Vec3 point) {
    if (minecraft.level == null || minecraft.player == null) return false;
    HitResult trace =
        minecraft.level.clip(
            new ClipContext(
                eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    return trace.getType() == HitResult.Type.MISS
        || eye.distanceToSqr(trace.getLocation()) + 1.0E-4 >= eye.distanceToSqr(point);
  }

  private static HitPoint point(LivingEntity entity, Sample sample) {
    AABB box = entity.getBoundingBox();
    return new HitPoint(
        new Vec3(
            lerp(box.minX, box.maxX, sample.x()),
            lerp(box.minY, box.maxY, sample.y()),
            lerp(box.minZ, box.maxZ, sample.z())),
        sample);
  }

  private static AimRotation rotationsTo(Vec3 eye, Vec3 point) {
    return AimMath.rotationsTo(eye.x, eye.y, eye.z, point.x, point.y, point.z);
  }

  private static double lerp(double minimum, double maximum, double amount) {
    return minimum + (maximum - minimum) * amount;
  }

  public record HitPoint(Vec3 position, Sample sample) {}

  public record Sample(double x, double y, double z, double penalty) {}
}
