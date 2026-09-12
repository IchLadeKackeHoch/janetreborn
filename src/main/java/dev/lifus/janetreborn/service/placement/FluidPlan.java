package dev.lifus.janetreborn.service.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record FluidPlan(BlockPos target, Vec3 hitVector, float yaw, float pitch) {}
