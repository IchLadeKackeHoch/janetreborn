package dev.lifus.janetreborn.feature.hud;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.config.ConfigNode;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import dev.lifus.janetreborn.ui.TitleView;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class WatermarkHudModule extends Module {
  private final HudPosition position = new HudPosition();
  private final Runnable drawHudSurface;
  private final NumberSetting<Integer> scale =
      add(new NumberSetting<>("Scale", 100, 50, 200, 5).unit("%").markAdvanced());

  @Subscribe private final Listener<FrameRenderEvent> render = this::render;

  public WatermarkHudModule(Runnable drawHudSurface) {
    super("Watermark", "Shows the client title", Category.HUD);
    this.drawHudSurface = drawHudSurface;
  }

  @Override
  public void writeConfig(ConfigNode config) {
    position.writeConfig(config, "hudPosition");
  }

  @Override
  public void readConfig(ConfigNode config) {
    position.readConfig(config, "hudPosition");
  }

  private void render(FrameRenderEvent event) {
    float titleScale = scale.getValue() / 100.0f;
    float width = TitleView.width(titleScale) + ImGui.getStyle().getWindowPaddingX() * 2;
    float height = ImGui.getFontSize() * titleScale + ImGui.getStyle().getWindowPaddingY() * 2;
    position.apply(width, height, (ImGui.getIO().getDisplaySizeX() - width) * 0.5f, 10);
    ImGui.setNextWindowSize(width, height);
    int flags =
        ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoNav;
    if (ImGui.begin("##WatermarkHUD", flags)) {
      drawHudSurface.run();
      position.capture(width, height);
      TitleView.draw(
          ImGui.getWindowDrawList(),
          ImGui.getCursorScreenPosX(),
          ImGui.getCursorScreenPosY(),
          titleScale);
    }
    ImGui.end();
  }
}
