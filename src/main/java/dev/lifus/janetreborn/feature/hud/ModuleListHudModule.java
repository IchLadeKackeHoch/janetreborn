package dev.lifus.janetreborn.feature.hud;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.config.ConfigNode;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import java.awt.Color;
import java.util.Comparator;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.Modules;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;

public final class ModuleListHudModule extends Module {
  private final HudPosition position = new HudPosition();
  private final Modules modules;
  private final Runnable drawHudSurface;
  private final BoolSetting showHudModules = add(new BoolSetting("Show HUD Modules", false));
  private final ColorSetting textColor =
      add(new ColorSetting("Text Color", new Color(215, 225, 255)));

  @Subscribe private final Listener<FrameRenderEvent> render = this::render;

  public ModuleListHudModule(Modules modules, Runnable drawHudSurface) {
    super("ModuleList", "Shows enabled modules", Category.HUD);
    this.modules = modules;
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
    var visibleModules =
        modules.getAll().stream()
            .filter(module -> module.isToggleable() && module.isEnabled())
            .filter(module -> showHudModules.getValue() || module.getCategory() != Category.HUD)
            .sorted(
                Comparator.comparingDouble((Module module) -> ImGui.calcTextSizeX(module.getName()))
                    .reversed())
            .toList();
    int flags =
        ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoNav;
    float width =
        (float)
            Math.max(
                120,
                visibleModules.stream()
                        .mapToDouble(module -> ImGui.calcTextSizeX(module.getName()))
                        .max()
                        .orElse(0)
                    + 16);
    float height = Math.max(32, visibleModules.size() * ImGui.getTextLineHeightWithSpacing() + 12);
    position.apply(width, height, 10, ClockHudModule.defaultModuleListY());
    ImGui.setNextWindowSize(width, height);
    if (ImGui.begin("##ModuleListHUD", flags)) {
      drawHudSurface.run();
      position.capture(width, height);
      boolean right =
          ImGui.getWindowPosX() + width * 0.5f >= ImGui.getIO().getDisplaySizeX() * 0.5f;
      for (Module module : visibleModules) {
        if (right)
          ImGui.setCursorPosX(
              ImGui.getWindowWidth()
                  - ImGui.getStyle().getWindowPaddingX()
                  - ImGui.calcTextSizeX(module.getName()));
        Color color = textColor.getValue();
        ImGui.textColored(
            color.getRed() / 255.0f,
            color.getGreen() / 255.0f,
            color.getBlue() / 255.0f,
            color.getAlpha() / 255.0f,
            module.getName());
      }
    }
    ImGui.end();
  }
}
