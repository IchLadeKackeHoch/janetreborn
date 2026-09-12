package dev.lifus.janetreborn.feature.hud;

import dev.lifus.janetreborn.config.ConfigNode;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;

public final class ClockHudModule extends Module {
  private static final float DEFAULT_X = 10;
  private static final float DEFAULT_Y = 10;
  private static final float DEFAULT_GAP = 8;
  private final HudPosition position = new HudPosition();
  private final Runnable drawHudSurface;
  private final ModeSetting<ClockFormat> format =
      add(new ModeSetting<>("Format", ClockFormat.TWENTY_FOUR_HOUR));
  private final BoolSetting showSeconds = add(new BoolSetting("Show Seconds", true));

  public ClockHudModule(Runnable drawHudSurface) {
    super("Clock", "Shows local time", Category.HUD);
    this.drawHudSurface = drawHudSurface;
  }

  @Override
  protected void onInitialize() {
    listen(FrameRenderEvent.class, this::render);
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
    String pattern =
        switch (format.getValue()) {
          case TWENTY_FOUR_HOUR -> showSeconds.getValue() ? "HH:mm:ss" : "HH:mm";
          case TWELVE_HOUR -> showSeconds.getValue() ? "hh:mm:ss a" : "hh:mm a";
        };
    String time = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
    float width = ImGui.calcTextSizeX(time) + ImGui.getStyle().getWindowPaddingX() * 2;
    float height = ImGui.getTextLineHeight() + ImGui.getStyle().getWindowPaddingY() * 2;
    int flags =
        ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoNav;
    position.apply(width, height, DEFAULT_X, DEFAULT_Y);
    ImGui.setNextWindowSize(width, height);
    if (ImGui.begin("##ClockHUD", flags)) {
      drawHudSurface.run();
      position.capture(width, height);
      ImGui.text(time);
    }
    ImGui.end();
  }

  static float defaultModuleListY() {
    return defaultModuleListY(ImGui.getTextLineHeight(), ImGui.getStyle().getWindowPaddingY());
  }

  static float defaultModuleListY(float textLineHeight, float paddingY) {
    return DEFAULT_Y + textLineHeight + paddingY * 2 + DEFAULT_GAP;
  }

  private enum ClockFormat {
    TWENTY_FOUR_HOUR("24-hour"),
    TWELVE_HOUR("12-hour");

    private final String label;

    ClockFormat(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
