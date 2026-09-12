package dev.lifus.janetreborn.platform.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
  @Invoker("setupRotations")
  void janetReborn$setupRotations(
      LivingEntityRenderState state, PoseStack poseStack, float bodyRotation, float scale);

  @Invoker("scale")
  void janetReborn$scale(LivingEntityRenderState state, PoseStack poseStack);
}
