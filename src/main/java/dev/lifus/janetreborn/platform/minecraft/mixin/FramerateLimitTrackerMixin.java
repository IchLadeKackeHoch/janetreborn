package dev.lifus.janetreborn.platform.minecraft.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
  @Shadow @Final private Minecraft minecraft;

  @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
  private void uncapMainMenu(CallbackInfoReturnable<Integer> callback) {
    if (minecraft.level == null
        && (minecraft.gui.screen() != null || minecraft.gui.overlay() != null)) {
      callback.setReturnValue(Integer.MAX_VALUE);
    }
  }
}
