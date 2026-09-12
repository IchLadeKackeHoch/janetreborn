package dev.lifus.janetreborn.ui;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;

/** Small, draw-list based controls used by the ClickGUI instead of ImGui's stock widgets. */
final class ControlKit {
  static final int TEXT = ImColor.rgb(225, 231, 237);
  static final int MUTED = ImColor.rgb(137, 149, 160);
  static final int ACCENT = ImColor.rgb(112, 174, 214);
  static final int ACCENT_HOVER = ImColor.rgb(132, 192, 228);
  static final int SURFACE = ImColor.rgb(39, 44, 49);
  static final int SURFACE_HOVER = ImColor.rgb(46, 53, 59);
  static final int SURFACE_ACTIVE = ImColor.rgb(52, 61, 68);
  static final int TRACK = ImColor.rgb(67, 75, 82);
  static final int BORDER = ImColor.rgb(65, 73, 80);

  private ControlKit() {}

  static boolean categoryHeader(String id, String label, int count, boolean expanded) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Category" + id, width, 42);
    boolean hovered = ImGui.isItemHovered();
    boolean active = ImGui.isItemActive();
    boolean clicked = ImGui.isItemClicked();
    ImDrawList draw = ImGui.getWindowDrawList();
    draw.addRectFilled(
        x, y, x + width, y + 42, active ? SURFACE_ACTIVE : hovered ? SURFACE_HOVER : SURFACE, 5);
    draw.addRect(x, y, x + width, y + 42, BORDER, 5);
    int arrow = hovered ? ACCENT_HOVER : MUTED;
    if (expanded) {
      draw.addLine(x + 16, y + 18, x + 21, y + 23, arrow, 1.5f);
      draw.addLine(x + 21, y + 23, x + 26, y + 18, arrow, 1.5f);
    } else {
      draw.addLine(x + 18, y + 16, x + 23, y + 21, arrow, 1.5f);
      draw.addLine(x + 23, y + 21, x + 18, y + 26, arrow, 1.5f);
    }
    draw.addText(x + 38, y + 12, TEXT, label.toUpperCase());
    String amount = Integer.toString(count);
    draw.addText(x + width - ImGui.calcTextSizeX(amount) - 14, y + 12, MUTED, amount);
    return clicked;
  }

  static boolean navItem(String id, String label, String marker, boolean selected) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Nav" + id, width, 40);
    boolean hovered = ImGui.isItemHovered();
    boolean active = ImGui.isItemActive();
    ImDrawList draw = ImGui.getWindowDrawList();
    if (selected || hovered) {
      draw.addRectFilled(
          x, y, x + width, y + 40, selected || active ? SURFACE_ACTIVE : SURFACE_HOVER, 4);
    }
    if (selected) draw.addRectFilled(x, y + 7, x + 3, y + 33, ACCENT, 2);
    draw.addText(x + 14, y + 11, selected ? ACCENT_HOVER : MUTED, marker);
    draw.addText(x + 42, y + 11, selected ? TEXT : hovered ? TEXT : MUTED, label);
    return ImGui.isItemClicked();
  }

  static boolean disclosure(String id, String label, boolean expanded) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Disclosure" + id, width, 34);
    boolean hovered = ImGui.isItemHovered();
    boolean active = ImGui.isItemActive();
    ImDrawList draw = ImGui.getWindowDrawList();
    draw.addRectFilled(
        x,
        y,
        x + width,
        y + 34,
        active ? SURFACE_ACTIVE : hovered ? SURFACE_HOVER : ImColor.rgb(34, 39, 43),
        4);
    draw.addText(x + 12, y + 8, expanded ? ACCENT_HOVER : MUTED, expanded ? "-" : "+");
    draw.addText(x + 34, y + 8, hovered || expanded ? TEXT : MUTED, label);
    return ImGui.isItemClicked();
  }

  static ModuleAction moduleRow(
      String id, String name, String description, boolean enabled, boolean expanded) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Module" + id, width, 58);
    boolean hovered = ImGui.isItemHovered();
    boolean active = ImGui.isItemActive();
    boolean left = ImGui.isItemClicked(0);
    boolean right = ImGui.isItemClicked(1);
    ImDrawList draw = ImGui.getWindowDrawList();
    int background =
        active
            ? SURFACE_ACTIVE
            : enabled ? ImColor.rgb(43, 53, 60) : hovered ? SURFACE_HOVER : SURFACE;
    draw.addRectFilled(x, y, x + width, y + 58, background, 5);
    draw.addRect(x, y, x + width, y + 58, enabled ? ImColor.rgb(69, 101, 120) : BORDER, 5);
    if (enabled) draw.addRectFilled(x, y + 8, x + 3, y + 50, ACCENT, 2);
    draw.addText(x + 14, y + 10, enabled ? ImColor.rgb(235, 244, 249) : TEXT, name);
    draw.addText(x + 14, y + 33, MUTED, ellipsize(description, width - 100));
    drawSwitch(draw, x + width - 52, y + 18, enabled, hovered, true, 38, 22);
    if (expanded) {
      draw.addCircleFilled(x + width - 61, y + 29, 2.5f, ACCENT);
    }
    return new ModuleAction(left, right);
  }

  static boolean toggle(String id, String label, boolean value, boolean enabled) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Toggle" + id, width, 36);
    boolean hovered = enabled && ImGui.isItemHovered();
    boolean active = enabled && ImGui.isItemActive();
    boolean clicked = enabled && ImGui.isItemClicked();
    ImDrawList draw = ImGui.getWindowDrawList();
    draw.addText(x + 2, y + 9, enabled ? TEXT : ImColor.rgb(91, 99, 106), label);
    drawSwitch(draw, x + width - 44, y + 7, value, hovered || active, enabled, 42, 22);
    return clicked;
  }

  static double slider(
      String id,
      String label,
      String displayedValue,
      double value,
      double minimum,
      double maximum,
      boolean enabled) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.invisibleButton("##Slider" + id, width, 48);
    boolean hovered = enabled && ImGui.isItemHovered();
    boolean active = enabled && ImGui.isItemActive();
    if (active && maximum > minimum) {
      double mouse = ImGui.getMousePosX();
      value = minimum + Math.clamp((mouse - x) / width, 0.0, 1.0) * (maximum - minimum);
    }
    double ratio =
        maximum <= minimum ? 0 : Math.clamp((value - minimum) / (maximum - minimum), 0, 1);
    ImDrawList draw = ImGui.getWindowDrawList();
    int labelColor = enabled ? TEXT : ImColor.rgb(91, 99, 106);
    draw.addText(x + 2, y + 3, labelColor, label);
    draw.addText(
        x + width - ImGui.calcTextSizeX(displayedValue) - 2,
        y + 3,
        enabled ? ACCENT : MUTED,
        displayedValue);
    float trackY = y + 35;
    draw.addRectFilled(x + 2, trackY, x + width - 2, trackY + 4, TRACK, 2);
    float fillX = x + 2 + (float) ratio * (width - 4);
    draw.addRectFilled(x + 2, trackY, fillX, trackY + 4, enabled ? ACCENT : MUTED, 2);
    draw.addCircleFilled(
        fillX,
        trackY + 2,
        active ? 6 : hovered ? 5.5f : 5,
        enabled ? (active ? ACCENT_HOVER : ACCENT) : MUTED);
    return value;
  }

  static void sectionLabel(String label) {
    float x = ImGui.getCursorScreenPosX();
    float y = ImGui.getCursorScreenPosY();
    float width = ImGui.getContentRegionAvailX();
    ImGui.dummy(width, 30);
    ImDrawList draw = ImGui.getWindowDrawList();
    draw.addText(x + 2, y + 8, ACCENT, label.toUpperCase());
    float lineStart = x + ImGui.calcTextSizeX(label.toUpperCase()) + 14;
    draw.addLine(lineStart, y + 17, x + width, y + 17, BORDER);
  }

  private static void drawSwitch(
      ImDrawList draw,
      float x,
      float y,
      boolean value,
      boolean hovered,
      boolean enabled,
      float width,
      float height) {
    int track =
        !enabled
            ? ImColor.rgb(51, 57, 62)
            : value
                ? (hovered ? ACCENT_HOVER : ACCENT)
                : hovered ? ImColor.rgb(82, 92, 100) : TRACK;
    draw.addRectFilled(x, y, x + width, y + height, track, height * 0.5f);
    float radius = height * 0.5f - 3;
    float knobX = value ? x + width - height * 0.5f : x + height * 0.5f;
    draw.addCircleFilled(
        knobX,
        y + height * 0.5f,
        radius,
        enabled ? ImColor.rgb(238, 242, 245) : ImColor.rgb(111, 119, 125));
  }

  private static String ellipsize(String value, float width) {
    if (value == null || value.isBlank()) return "No description";
    if (ImGui.calcTextSizeX(value) <= width) return value;
    String shortened = value;
    while (shortened.length() > 1 && ImGui.calcTextSizeX(shortened + "...") > width) {
      shortened = shortened.substring(0, shortened.length() - 1);
    }
    return shortened + "...";
  }

  record ModuleAction(boolean toggle, boolean expand) {}
}
