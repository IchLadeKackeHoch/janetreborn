package dev.lifus.janetreborn.api;

import java.util.Optional;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;

public interface Network {
  Optional<ClientPacketListener> connection();

  boolean send(Packet<?> packet);
}
