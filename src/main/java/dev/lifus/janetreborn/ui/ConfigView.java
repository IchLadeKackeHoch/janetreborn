package dev.lifus.janetreborn.ui;

import dev.lifus.janetreborn.config.Configs;
import imgui.ImGui;
import imgui.type.ImInt;
import imgui.type.ImString;

final class ConfigView {
  private final Configs configs;
  private final ImInt selectedIndex = new ImInt();
  private final ImString configName = new ImString(65);
  private String status = "";

  ConfigView(Configs configs) {
    this.configs = configs;
  }

  void draw() {
    String[] names = configs.getUserConfigs();
    if (selectedIndex.get() >= names.length) {
      selectedIndex.set(Math.max(0, names.length - 1));
    }
    ImGui.text("Saved Configurations");
    if (ImGui.beginChild("##ConfigList", 0, 245, true)) {
      if (names.length == 0) ImGui.textDisabled("No saved configurations");
      for (int index = 0; index < names.length; index++) {
        if (ImGui.selectable(names[index], selectedIndex.get() == index)) {
          selectedIndex.set(index);
          configName.set(names[index]);
        }
      }
    }
    ImGui.endChild();
    ImGui.setNextItemWidth(260);
    ImGui.inputTextWithHint("##ConfigName", "Config name", configName);
    if (ImGui.button("Save")) save();
    if (names.length > 0) drawActions(names[selectedIndex.get()]);
    if (!status.isEmpty()) ImGui.textDisabled(status);
  }

  private void save() {
    String name = configs.saveUserConfig(configName.get());
    status = name == null ? "Save failed" : "Saved " + name;
    if (name == null) return;
    String[] names = configs.getUserConfigs();
    for (int index = 0; index < names.length; index++) {
      if (names[index].equals(name)) selectedIndex.set(index);
    }
  }

  private void drawActions(String selected) {
    ImGui.sameLine();
    if (ImGui.button("Load")) {
      status = configs.loadUserConfig(selected) ? "Loaded " + selected : "Load failed";
    }
    ImGui.sameLine();
    if (ImGui.button("Rename")) {
      status =
          configs.renameUserConfig(selected, configName.get())
              ? "Renamed " + selected
              : "Rename failed";
    }
    ImGui.sameLine();
    if (ImGui.button("Delete")) {
      boolean deleted = configs.deleteUserConfig(selected);
      status = deleted ? "Deleted " + selected : "Delete failed";
      if (deleted) configName.clear();
    }
  }
}
