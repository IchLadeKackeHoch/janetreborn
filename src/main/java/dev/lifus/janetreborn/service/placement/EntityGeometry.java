package dev.lifus.janetreborn.service.placement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EntityGeometry {
  private static final double EDGE_EPSILON = 1.0E-4;
  private static final double MINIMUM_AIR_MOVEMENT_SQUARED = 1.0E-4;

  private EntityGeometry() {}

  public static Set<BlockPos> occupied(
      Entity entity, double minimumHorizontalArea, double minimumVerticalOverlap) {
    return occupied(entity.getBoundingBox(), minimumHorizontalArea, minimumVerticalOverlap);
  }

  public static Set<BlockPos> enteredNextStep(
      Entity entity,
      Vec3 observedMovement,
      double minimumHorizontalArea,
      double minimumVerticalOverlap) {
    Vec3 movement = Objects.requireNonNull(observedMovement, "observedMovement");
    if (movement.lengthSqr() < MINIMUM_AIR_MOVEMENT_SQUARED) return Set.of();
    AABB bounds = entity.getBoundingBox();
    Vec3 unobstructed =
        Entity.collideBoundingBox(entity, movement, bounds, entity.level(), List.of());
    if (unobstructed.lengthSqr() < MINIMUM_AIR_MOVEMENT_SQUARED) return Set.of();
    return occupied(bounds.move(unobstructed), minimumHorizontalArea, minimumVerticalOverlap);
  }

  public static Set<BlockPos> occupied(
      AABB bounds, double minimumHorizontalArea, double minimumVerticalOverlap) {
    Set<BlockPos> result = new LinkedHashSet<>();
    int minX = (int) Math.floor(bounds.minX + EDGE_EPSILON);
    int maxX = (int) Math.floor(bounds.maxX - EDGE_EPSILON);
    int minY = (int) Math.floor(bounds.minY + EDGE_EPSILON);
    int maxY = (int) Math.floor(bounds.maxY - EDGE_EPSILON);
    int minZ = (int) Math.floor(bounds.minZ + EDGE_EPSILON);
    int maxZ = (int) Math.floor(bounds.maxZ - EDGE_EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          BlockPos cell = new BlockPos(x, y, z);
          AABB block = new AABB(cell);
          double overlapX = overlap(bounds.minX, bounds.maxX, block.minX, block.maxX);
          double overlapY = overlap(bounds.minY, bounds.maxY, block.minY, block.maxY);
          double overlapZ = overlap(bounds.minZ, bounds.maxZ, block.minZ, block.maxZ);
          if (overlapX * overlapZ >= minimumHorizontalArea && overlapY >= minimumVerticalOverlap)
            result.add(cell);
        }
      }
    }
    return result;
  }

  private static double overlap(
      double firstMin, double firstMax, double secondMin, double secondMax) {
    return Math.max(0.0, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin));
  }
}
