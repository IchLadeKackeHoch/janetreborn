package dev.lifus.janetreborn.feature.combat.crystal;

import dev.lifus.janetreborn.service.placement.PlacementPlan;
import net.minecraft.core.BlockPos;

record CrystalSite(BlockPos base, PlacementPlan basePlacement) {
  CrystalSite {
    base = base.immutable();
  }

  boolean needsBase() {
    return basePlacement != null;
  }
}
