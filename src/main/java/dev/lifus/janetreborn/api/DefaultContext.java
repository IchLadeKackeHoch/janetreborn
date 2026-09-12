package dev.lifus.janetreborn.api;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.command.Commands;
import dev.lifus.janetreborn.config.Configs;
import dev.lifus.janetreborn.service.Services;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import opsec.misuyaka.sdk.Modules;

public record DefaultContext(
    EventBus events,
    Modules modules,
    Services services,
    Minecraft minecraft,
    Network network,
    Render rendering,
    Configs configs,
    Commands commands)
    implements ClientContext {
  public DefaultContext {
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(modules, "modules");
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(minecraft, "minecraft");
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(rendering, "rendering");
    Objects.requireNonNull(configs, "configs");
    Objects.requireNonNull(commands, "commands");
  }
}
