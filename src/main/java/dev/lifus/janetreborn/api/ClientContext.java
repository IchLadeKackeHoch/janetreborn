package dev.lifus.janetreborn.api;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.command.Commands;
import dev.lifus.janetreborn.config.Configs;
import dev.lifus.janetreborn.service.Services;
import net.minecraft.client.Minecraft;
import opsec.misuyaka.sdk.Modules;

public interface ClientContext {
  EventBus events();

  Modules modules();

  Services services();

  Minecraft minecraft();

  Network network();

  Render rendering();

  Configs configs();

  Commands commands();
}
