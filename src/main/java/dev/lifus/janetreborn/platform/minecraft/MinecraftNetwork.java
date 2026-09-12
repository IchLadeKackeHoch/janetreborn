package dev.lifus.janetreborn.platform.minecraft;

import dev.lifus.janetreborn.api.Network;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;

@RequiredArgsConstructor
public final class MinecraftNetwork implements Network {
  @NonNull private final Minecraft minecraft;

  @Override
  public Optional<ClientPacketListener> connection() {
    return Optional.ofNullable(minecraft.getConnection());
  }

  @Override
  public boolean send(Packet<?> packet) {
    Objects.requireNonNull(packet, "packet");
    ClientPacketListener connection = minecraft.getConnection();
    if (connection == null) return false;
    connection.send(packet);
    return true;
  }
}
