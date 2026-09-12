package dev.lifus.janetreborn.service.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

public record PlacementPlan(
    BlockPos target, BlockHitResult hit, float yaw, float pitch, double score) {}
