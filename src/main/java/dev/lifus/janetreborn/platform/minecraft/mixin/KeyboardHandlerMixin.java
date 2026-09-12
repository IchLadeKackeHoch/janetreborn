package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.KeyInputEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
    KeyInputEvent scriptEvent = new KeyInputEvent(event.key(), action);
    JanetClient.instance().events().publish(scriptEvent);
    if (scriptEvent.isCancelled()) {
      ci.cancel();
      return;
    }
    var renderer = JanetClient.instance().ui();
    if (renderer.isOpen() && !renderer.isMovementKey(event)) ci.cancel();
  }

  @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
  private void charTyped(long window, CharacterEvent event, CallbackInfo ci) {
    if (JanetClient.instance().ui().isOpen()) ci.cancel();
  }
}
