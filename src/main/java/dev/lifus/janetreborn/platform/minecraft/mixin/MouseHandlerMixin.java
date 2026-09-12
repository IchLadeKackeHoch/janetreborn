package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.MouseInputEvent;
import dev.lifus.janetreborn.feature.combat.AimAssistModule;
import dev.lifus.janetreborn.feature.misc.FriendsModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
  @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
  private void move(long window, double x, double y, CallbackInfo ci) {
    if (JanetClient.instance().ui().isOpen()) ci.cancel();
  }

  @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
  private void button(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
    JanetClient client = JanetClient.instance();
    MouseInputEvent scriptEvent = new MouseInputEvent("button", button.button(), action, 0, 0);
    client.events().publish(scriptEvent);
    if (scriptEvent.isCancelled()) {
      ci.cancel();
      return;
    }
    if (client.ui().isOpen()) {
      ci.cancel();
      return;
    }
    if (button.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || action != GLFW.GLFW_PRESS) return;
    FriendsModule friends = client.modules().get(FriendsModule.class);
    if (friends != null && friends.handleMiddleClick()) ci.cancel();
  }

  @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
  private void scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
    MouseInputEvent scriptEvent = new MouseInputEvent("scroll", -1, 0, horizontal, vertical);
    JanetClient.instance().events().publish(scriptEvent);
    if (scriptEvent.isCancelled()) {
      ci.cancel();
      return;
    }
    if (JanetClient.instance().ui().isOpen()) ci.cancel();
  }

  @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
  private void turn(double time, CallbackInfo ci) {
    if (JanetClient.instance().ui().isOpen()) ci.cancel();
  }

  @Inject(method = "turnPlayer", at = @At("RETURN"))
  private void applyAimAssist(double time, CallbackInfo ci) {
    AimAssistModule module = JanetClient.instance().modules().get(AimAssistModule.class);
    if (module != null) module.updateCamera();
  }
}
