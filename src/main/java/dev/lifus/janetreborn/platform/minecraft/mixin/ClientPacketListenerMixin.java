package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.ChatSendEvent;
import dev.lifus.janetreborn.event.game.TotemActivatedEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.EntityEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
  @Inject(method = "handleMovePlayer", at = @At("TAIL"))
  private void handleServerTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
    JanetClient.instance().teleports().handleIncomingTeleport();
  }

  @Inject(method = "handleEntityEvent", at = @At("TAIL"))
  private void handleTotemActivation(ClientboundEntityEventPacket packet, CallbackInfo ci) {
    var minecraft = net.minecraft.client.Minecraft.getInstance();
    if (minecraft.player != null
        && minecraft.level != null
        && packet.getEventId() == EntityEvent.PROTECTED_FROM_DEATH
        && packet.getEntity(minecraft.level) == minecraft.player) {
      JanetClient.instance().events().publish(new TotemActivatedEvent());
    }
  }

  @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
  private void handleClientCommand(String message, CallbackInfo callback) {
    JanetClient client = JanetClient.instance();
    if (client.commands().dispatchChat(message)) {
      callback.cancel();
      return;
    }
    ChatSendEvent event = new ChatSendEvent(message);
    client.events().publish(event);
    if (event.isCancelled()) callback.cancel();
  }
}
