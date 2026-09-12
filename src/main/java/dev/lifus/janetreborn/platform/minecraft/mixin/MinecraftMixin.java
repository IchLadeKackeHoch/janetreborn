package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.feature.combat.AttributeSwapModule;
import dev.lifus.janetreborn.feature.combat.ShieldBreakerModule;
import dev.lifus.janetreborn.feature.combat.TriggerbotModule;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
  @Inject(method = "<init>", at = @At("TAIL"))
  private void init(CallbackInfo ci) {
    GLFW.glfwMaximizeWindow(Minecraft.getInstance().getWindow().handle());
    JanetClient.instance().init();
  }

  @Inject(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V",
              shift = At.Shift.AFTER))
  private void render(boolean tick, CallbackInfo ci) {
    JanetClient.instance().ui().render(Minecraft.getInstance().getDeltaTracker());
  }

  @Inject(method = "tick", at = @At("HEAD"))
  private void tick(CallbackInfo ci) {
    JanetClient client = JanetClient.instance();
    Minecraft minecraft = Minecraft.getInstance();
    client.modules().updateKeybinds(minecraft.getWindow().handle(), client.ui().isOpen());
    client.rotations().tick();
    client.combat().tick();
    client.inventoryActions().tick();
    client.ownedFluids().tick();
    client.teleports().tick();
    client.scripts().tick();
    client.modules().update();
    client.events().publish(new TickEvent());
  }

  @Inject(method = "tick", at = @At("RETURN"))
  private void tickEnd(CallbackInfo ci) {
    JanetClient client = JanetClient.instance();
    client.predictor().tick();
    client.hotbar().endTick();
    client.events().publish(new TickEndEvent());
  }

  @Inject(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/Minecraft;handleKeybinds()V",
              shift = At.Shift.AFTER))
  private void runPostInputAutomation(CallbackInfo ci) {
    JanetClient.instance().events().publish(new PostInputEvent());
    TriggerbotModule module = JanetClient.instance().modules().get(TriggerbotModule.class);
    if (module != null) module.tryVanillaAttack();
  }

  @Inject(
      method = "startAttack",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
  private void prepareCombatAttack(CallbackInfoReturnable<Boolean> cir) {
    AttributeSwapModule attributeSwap =
        JanetClient.instance().modules().get(AttributeSwapModule.class);
    if (attributeSwap != null) attributeSwap.prepareAttack();
    ShieldBreakerModule module = JanetClient.instance().modules().get(ShieldBreakerModule.class);
    if (module != null) module.prepareVanillaAttack();
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void shutdown(CallbackInfo ci) {
    JanetClient.instance().shutdown();
  }
}
