package dev.lifus.janetreborn.api;

import com.mojang.blaze3d.pipeline.RenderTarget;

public interface Render {
  void drawHudSurface();

  RenderTarget mainTarget();
}
