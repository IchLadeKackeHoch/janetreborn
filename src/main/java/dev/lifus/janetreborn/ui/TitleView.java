package dev.lifus.janetreborn.ui;

import dev.lifus.janetreborn.client.JanetClient;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.util.function.ToDoubleFunction;

public final class TitleView {
  private TitleView() {}

  public static float width() {
    return width(1.0f);
  }

  public static float width(float scale) {
    return segmentedWidth(
        JanetClient.NAME, JanetClient.VERSION, effectiveScale(scale), ImGui::calcTextSizeX);
  }

  public static void draw(ImDrawList drawList, float x, float y) {
    draw(drawList, x, y, 1.0f);
  }

  public static void draw(ImDrawList drawList, float x, float y, float scale) {
    String title = JanetClient.NAME;
    int fontSize = scaledFontSize(scale);
    float actualScale = fontSize / (float) ImGui.getFontSize();
    float animation = (System.nanoTime() / 1_000_000_000f * 0.35f) % 2;
    for (int i = 0; i < title.length(); i++) {
      String letter = title.substring(i, i + 1);
      float progress = title.length() == 1 ? 0 : i / (float) (title.length() - 1);
      float wave = (progress * 2 + animation) % 2;
      float blend = 1 - Math.abs(wave - 1);
      int red = Math.round(100 + 80 * blend);
      int green = Math.round(200 - 110 * blend);
      drawList.addText(ImGui.getFont(), fontSize, x, y, ImColor.rgba(red, green, 255, 255), letter);
      x += ImGui.calcTextSizeX(letter) * actualScale;
    }
    drawList.addText(
        ImGui.getFont(),
        fontSize,
        x,
        y,
        ImColor.rgba(255, 255, 255, 255),
        " " + JanetClient.VERSION);
  }

  static float segmentedWidth(
      String title, String version, float scale, ToDoubleFunction<String> measure) {
    double width = measure.applyAsDouble(" " + version);
    for (int i = 0; i < title.length(); i++) {
      width += measure.applyAsDouble(title.substring(i, i + 1));
    }
    return (float) (width * scale);
  }

  private static int scaledFontSize(float scale) {
    return Math.max(1, Math.round(ImGui.getFontSize() * scale));
  }

  private static float effectiveScale(float scale) {
    return scaledFontSize(scale) / (float) ImGui.getFontSize();
  }
}
