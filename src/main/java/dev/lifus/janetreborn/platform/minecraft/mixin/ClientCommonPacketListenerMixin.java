package dev.lifus.janetreborn.platform.minecraft.mixin;

import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.feature.misc.PackSpoofModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
  @Shadow
  public abstract void send(Packet<?> packet);

  @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
  private void spoofResourcePack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
    PackSpoofModule module = JanetClient.instance().modules().get(PackSpoofModule.class);
    if (module == null || !module.isEnabled()) return;

    send(
        new ServerboundResourcePackPacket(
            packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
    send(
        new ServerboundResourcePackPacket(
            packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
    send(
        new ServerboundResourcePackPacket(
            packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
    JanetClient.instance().notices().hud("PackSpoof", "Skipped the server resource pack");
    ci.cancel();
  }
}
