package dev.lifus.janetreborn.platform.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.lifus.janetreborn.api.Render;
import dev.lifus.janetreborn.ui.ClientUi;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.minecraft.client.Minecraft;

@RequiredArgsConstructor
public final class MinecraftRender implements Render {
  @NonNull private final Minecraft minecraft;
  @NonNull private final ClientUi renderer;

  @Override
  public void drawHudSurface() {
    renderer.drawHudSurface();
  }

  @Override
  public RenderTarget mainTarget() {
    return minecraft.gameRenderer.mainRenderTarget();
  }
}
