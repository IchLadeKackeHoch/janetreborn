package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.service.rotation.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
  @Inject(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
      at = @At("TAIL"))
  private void visualizeServerRotation(
      Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
    Minecraft minecraft = Minecraft.getInstance();
    if ((Object) avatar != minecraft.player) return;
    Rotations rotations = JanetClient.instance().rotations();
    if (!rotations.isArtificialRotationActive()) return;
    float yaw = rotations.getVisibleYaw(partialTick);
    state.yRot = wrapDegrees(yaw - state.bodyRot);
    state.xRot = rotations.getVisiblePitch(partialTick);
  }

  private static float wrapDegrees(float value) {
    float wrapped = value % 360.0f;
    if (wrapped >= 180.0f) wrapped -= 360.0f;
    if (wrapped < -180.0f) wrapped += 360.0f;
    return wrapped;
  }
}
