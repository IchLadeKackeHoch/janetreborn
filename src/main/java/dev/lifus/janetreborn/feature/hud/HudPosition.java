package dev.lifus.janetreborn.feature.hud;

import com.google.gson.JsonObject;
import dev.lifus.janetreborn.config.ConfigNode;
import imgui.ImGui;
import imgui.flag.ImGuiCond;

public final class HudPosition {
  private Axis horizontal = Axis.CENTER;
  private Axis vertical = Axis.CENTER;
  private boolean initialized;
  private float horizontalOffset;
  private float verticalOffset;
  private float displayWidth;
  private float displayHeight;
  private float width;
  private float height;
  private long fontPointer;

  public void apply(float width, float height, float defaultX, float defaultY) {
    float displayWidth = ImGui.getIO().getDisplaySizeX();
    float displayHeight = ImGui.getIO().getDisplaySizeY();
    long fontPointer = ImGui.getFont().ptr;
    if (!initialized) ImGui.setNextWindowPos(defaultX, defaultY, ImGuiCond.FirstUseEver);
    else if (displayWidth != this.displayWidth
        || displayHeight != this.displayHeight
        || width != this.width
        || height != this.height
        || fontPointer != this.fontPointer) {
      float x =
          switch (horizontal) {
            case START -> horizontalOffset;
            case CENTER -> (displayWidth - width) * 0.5f + horizontalOffset;
            case END -> displayWidth - width - horizontalOffset;
          };
      float y =
          switch (vertical) {
            case START -> verticalOffset;
            case CENTER -> (displayHeight - height) * 0.5f + verticalOffset;
            case END -> displayHeight - height - verticalOffset;
          };
      ImGui.setNextWindowPos(
          clamp(x, displayWidth - width), clamp(y, displayHeight - height), ImGuiCond.Always);
    }
  }

  public void capture(float width, float height) {
    float displayWidth = ImGui.getIO().getDisplaySizeX();
    float displayHeight = ImGui.getIO().getDisplaySizeY();
    float x = clamp(ImGui.getWindowPosX(), displayWidth - width);
    float y = clamp(ImGui.getWindowPosY(), displayHeight - height);
    if (x != ImGui.getWindowPosX() || y != ImGui.getWindowPosY()) ImGui.setWindowPos(x, y);
    horizontal = axis(x + width * 0.5f, displayWidth);
    vertical = axis(y + height * 0.5f, displayHeight);
    horizontalOffset =
        switch (horizontal) {
          case START -> x;
          case CENTER -> x + width * 0.5f - displayWidth * 0.5f;
          case END -> displayWidth - x - width;
        };
    verticalOffset =
        switch (vertical) {
          case START -> y;
          case CENTER -> y + height * 0.5f - displayHeight * 0.5f;
          case END -> displayHeight - y - height;
        };
    this.displayWidth = displayWidth;
    this.displayHeight = displayHeight;
    this.width = width;
    this.height = height;
    this.fontPointer = ImGui.getFont().ptr;
    initialized = true;
  }

  public void writeConfig(ConfigNode config, String key) {
    if (!initialized) return;
    JsonObject encoded = new JsonObject();
    encoded.addProperty("horizontal", horizontal.name());
    encoded.addProperty("vertical", vertical.name());
    encoded.addProperty("horizontalOffset", horizontalOffset);
    encoded.addProperty("verticalOffset", verticalOffset);
    config.put(key, encoded);
  }

  public void readConfig(ConfigNode config, String key) {
    config
        .element(key)
        .filter(element -> element.isJsonObject())
        .ifPresent(
            element -> {
              JsonObject encoded = element.getAsJsonObject();
              try {
                horizontal = Axis.valueOf(encoded.get("horizontal").getAsString());
                vertical = Axis.valueOf(encoded.get("vertical").getAsString());
                horizontalOffset = encoded.get("horizontalOffset").getAsFloat();
                verticalOffset = encoded.get("verticalOffset").getAsFloat();
                displayWidth = Float.NaN;
                displayHeight = Float.NaN;
                initialized = true;
              } catch (RuntimeException ignored) {
                // Ignore malformed or obsolete HUD positions.
              }
            });
  }

  private Axis axis(float center, float size) {
    if (center < size / 3) return Axis.START;
    return center > size * 2 / 3 ? Axis.END : Axis.CENTER;
  }

  private float clamp(float value, float maximum) {
    return Math.max(0, Math.min(value, Math.max(0, maximum)));
  }

  private enum Axis {
    START,
    CENTER,
    END
  }
}
