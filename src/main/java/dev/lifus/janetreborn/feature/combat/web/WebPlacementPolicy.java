package dev.lifus.janetreborn.feature.combat.web;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

final class WebPlacementPolicy {
  static final int MAX_PLACEMENTS_PER_ACTION = 2;
  static final int MAX_PLACEMENTS_PER_TARGET = 2;

  private WebPlacementPolicy() {}

  static LinkedHashSet<BlockPos> rankCandidates(
      BlockPos currentCenter,
      Set<BlockPos> currentCells,
      BlockPos nearCenter,
      Set<BlockPos> nearCells,
      BlockPos mainCenter,
      Set<BlockPos> mainCells,
      BlockPos farCenter,
      Set<BlockPos> farCells,
      boolean hindered) {
    LinkedHashSet<BlockPos> ranked = new LinkedHashSet<>();
    if (hindered) {
      ranked.add(currentCenter);
      ranked.addAll(currentCells);
      return ranked;
    }

    for (BlockPos cell : mainCells) {
      if (farCells.contains(cell)) ranked.add(cell);
    }
    ranked.add(mainCenter);
    ranked.addAll(mainCells);
    ranked.add(farCenter);
    ranked.addAll(farCells);
    ranked.add(nearCenter);
    ranked.addAll(nearCells);
    ranked.add(currentCenter);
    ranked.addAll(currentCells);
    return ranked;
  }

  static boolean mayRestartAction(
      int targetPlacements, int actionPlacements, double displacementSquared) {
    return mayRestartAction(
        targetPlacements, actionPlacements, displacementSquared, MAX_PLACEMENTS_PER_TARGET);
  }

  static boolean mayRestartAction(
      int targetPlacements, int actionPlacements, double displacementSquared, int maximum) {
    return targetPlacements < maximum && actionPlacements > 0 && displacementSquared >= 2.25;
  }

  static boolean mayAttempt(int targetPlacements, int actionPlacements) {
    return mayAttempt(targetPlacements, actionPlacements, MAX_PLACEMENTS_PER_TARGET);
  }

  static boolean mayAttempt(int targetPlacements, int actionPlacements, int maximum) {
    return targetPlacements < maximum && actionPlacements < MAX_PLACEMENTS_PER_ACTION;
  }

  static boolean isValuableSecond(
      BlockPos firstPlacement, BlockPos candidate, Set<BlockPos> currentTargetCells) {
    if (firstPlacement == null
        || candidate == null
        || !currentTargetCells.contains(firstPlacement)
        || !currentTargetCells.contains(candidate)) return false;
    return firstPlacement.getX() == candidate.getX()
        && firstPlacement.getZ() == candidate.getZ()
        && Math.abs(firstPlacement.getY() - candidate.getY()) == 1;
  }
}
