package dev.lifus.janetreborn.ui;

import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;

/** The opaque Slate/Signal visual system used across the client interface. */
final class Theme {
  private Theme() {}

  static void apply() {
    ImGuiStyle style = ImGui.getStyle();
    style.setAlpha(1);
    style.setDisabledAlpha(0.42f);
    style.setWindowPadding(18, 18);
    style.setWindowRounding(7);
    style.setWindowBorderSize(1);
    style.setWindowTitleAlign(0, 0.5f);
    style.setWindowMenuButtonPosition(ImGuiDir.None);
    style.setChildRounding(5);
    style.setChildBorderSize(1);
    style.setPopupRounding(5);
    style.setPopupBorderSize(1);
    style.setFramePadding(10, 7);
    style.setFrameRounding(4);
    style.setFrameBorderSize(1);
    style.setItemSpacing(8, 8);
    style.setItemInnerSpacing(8, 6);
    style.setIndentSpacing(18);
    style.setScrollbarSize(10);
    style.setScrollbarRounding(4);
    style.setGrabMinSize(12);
    style.setGrabRounding(3);
    style.setTabRounding(4);
    style.setTabBorderSize(0);
    style.setColorButtonPosition(ImGuiDir.Right);

    color(style, ImGuiCol.Text, 0.88f, 0.91f, 0.93f, 1);
    color(style, ImGuiCol.TextDisabled, 0.54f, 0.59f, 0.63f, 1);
    color(style, ImGuiCol.WindowBg, 0.10f, 0.12f, 0.13f, 1);
    color(style, ImGuiCol.ChildBg, 0.13f, 0.15f, 0.17f, 1);
    color(style, ImGuiCol.PopupBg, 0.15f, 0.17f, 0.19f, 1);
    color(style, ImGuiCol.Border, 0.25f, 0.29f, 0.32f, 1);
    color(style, ImGuiCol.BorderShadow, 0.06f, 0.07f, 0.08f, 1);
    color(style, ImGuiCol.FrameBg, 0.18f, 0.21f, 0.23f, 1);
    color(style, ImGuiCol.FrameBgHovered, 0.23f, 0.27f, 0.30f, 1);
    color(style, ImGuiCol.FrameBgActive, 0.27f, 0.32f, 0.35f, 1);
    color(style, ImGuiCol.TitleBg, 0.10f, 0.12f, 0.13f, 1);
    color(style, ImGuiCol.TitleBgActive, 0.10f, 0.12f, 0.13f, 1);
    color(style, ImGuiCol.TitleBgCollapsed, 0.10f, 0.12f, 0.13f, 1);
    color(style, ImGuiCol.MenuBarBg, 0.13f, 0.15f, 0.17f, 1);
    color(style, ImGuiCol.ScrollbarBg, 0.10f, 0.12f, 0.13f, 1);
    color(style, ImGuiCol.ScrollbarGrab, 0.29f, 0.34f, 0.37f, 1);
    color(style, ImGuiCol.ScrollbarGrabHovered, 0.36f, 0.43f, 0.47f, 1);
    color(style, ImGuiCol.ScrollbarGrabActive, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.CheckMark, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.SliderGrab, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.SliderGrabActive, 0.52f, 0.75f, 0.89f, 1);
    color(style, ImGuiCol.Button, 0.18f, 0.21f, 0.23f, 1);
    color(style, ImGuiCol.ButtonHovered, 0.25f, 0.30f, 0.33f, 1);
    color(style, ImGuiCol.ButtonActive, 0.29f, 0.36f, 0.40f, 1);
    color(style, ImGuiCol.Header, 0.20f, 0.24f, 0.27f, 1);
    color(style, ImGuiCol.HeaderHovered, 0.25f, 0.30f, 0.33f, 1);
    color(style, ImGuiCol.HeaderActive, 0.29f, 0.36f, 0.40f, 1);
    color(style, ImGuiCol.Separator, 0.25f, 0.29f, 0.32f, 1);
    color(style, ImGuiCol.SeparatorHovered, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.SeparatorActive, 0.52f, 0.75f, 0.89f, 1);
    color(style, ImGuiCol.ResizeGrip, 0.29f, 0.34f, 0.37f, 1);
    color(style, ImGuiCol.ResizeGripHovered, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.ResizeGripActive, 0.52f, 0.75f, 0.89f, 1);
    color(style, ImGuiCol.Tab, 0.13f, 0.15f, 0.17f, 1);
    color(style, ImGuiCol.TabHovered, 0.25f, 0.30f, 0.33f, 1);
    color(style, ImGuiCol.TabActive, 0.27f, 0.36f, 0.42f, 1);
    color(style, ImGuiCol.TabUnfocused, 0.13f, 0.15f, 0.17f, 1);
    color(style, ImGuiCol.TabUnfocusedActive, 0.20f, 0.24f, 0.27f, 1);
    color(style, ImGuiCol.TableHeaderBg, 0.18f, 0.21f, 0.23f, 1);
    color(style, ImGuiCol.TableBorderStrong, 0.25f, 0.29f, 0.32f, 1);
    color(style, ImGuiCol.TableBorderLight, 0.21f, 0.24f, 0.27f, 1);
    color(style, ImGuiCol.TableRowBg, 0.13f, 0.15f, 0.17f, 1);
    color(style, ImGuiCol.TableRowBgAlt, 0.15f, 0.17f, 0.19f, 1);
    color(style, ImGuiCol.TextSelectedBg, 0.27f, 0.45f, 0.57f, 1);
    color(style, ImGuiCol.DragDropTarget, 0.52f, 0.75f, 0.89f, 1);
    color(style, ImGuiCol.NavHighlight, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.NavWindowingHighlight, 0.44f, 0.68f, 0.84f, 1);
    color(style, ImGuiCol.NavWindowingDimBg, 0.08f, 0.10f, 0.11f, 1);
    color(style, ImGuiCol.ModalWindowDimBg, 0.08f, 0.10f, 0.11f, 1);
  }

  private static void color(
      ImGuiStyle style, int index, float red, float green, float blue, float alpha) {
    style.setColor(index, red, green, blue, alpha);
  }
}
