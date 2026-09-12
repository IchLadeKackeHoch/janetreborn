package dev.lifus.janetreborn.ui;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.lifus.janetreborn.feature.misc.FriendsModule;
import dev.lifus.janetreborn.feature.misc.RadioModule;
import dev.lifus.janetreborn.feature.player.InventoryManagerModule;
import dev.lifus.janetreborn.scripting.ScriptModule;
import dev.lifus.janetreborn.scripting.ScriptSetting;
import dev.lifus.janetreborn.scripting.ScriptSettingKind;
import imgui.ImGui;
import imgui.flag.ImGuiColorEditFlags;
import imgui.type.ImString;
import java.awt.Color;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import opsec.misuyaka.sdk.BindMode;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.Modules;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;
import opsec.misuyaka.sdk.setting.KeySetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.RangeSetting;
import opsec.misuyaka.sdk.setting.Setting;
import org.lwjgl.glfw.GLFW;

final class ModuleView {
  private final Modules modules;
  private final Minecraft minecraft;
  private final InventoryManagerView inventoryManagerView;
  private final Gson gson = new Gson();
  private final Map<ScriptSetting, ImString> scriptInputs = new IdentityHashMap<>();
  private final Map<ScriptModule, Boolean> scriptAdvanced = new IdentityHashMap<>();
  private final Map<Category, Boolean> categoryExpanded = new EnumMap<>(Category.class);
  private final Map<Module, Boolean> moduleExpanded = new IdentityHashMap<>();
  private final Map<Module, Boolean> advancedExpanded = new IdentityHashMap<>();
  private final ImString friendInput = new ImString(128);
  private Module binding;

  ModuleView(Minecraft minecraft, Modules modules) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.modules = Objects.requireNonNull(modules, "modules");
    this.inventoryManagerView = new InventoryManagerView(this::drawSetting);
  }

  static String categoryName(Category category) {
    return displayName(category.name());
  }

  void drawAllCategories() {
    for (Category category : Category.values()) {
      drawCategory(category);
      ImGui.dummy(0, 2);
    }
  }

  void drawCategory(Category category) {
    List<Module> visibleModules =
        modules.getAll().stream()
            .filter(module -> module.isToggleable() && module.getCategory() == category)
            .filter(module -> !(module instanceof ScriptModule))
            .sorted(Comparator.comparing(Module::getName))
            .toList();
    boolean expanded = categoryExpanded.getOrDefault(category, true);
    if (ControlKit.categoryHeader(
        category.name(), categoryName(category), visibleModules.size(), expanded)) {
      expanded = !expanded;
      categoryExpanded.put(category, expanded);
    }
    if (!expanded) return;
    ImGui.indent(14);
    ImGui.dummy(0, 2);
    if (visibleModules.isEmpty()) ImGui.textDisabled("No modules in this category");
    else
      for (Module module : visibleModules) {
        drawModule(module);
        ImGui.dummy(0, 1);
      }
    ImGui.unindent(14);
  }

  void drawSettings() {
    var settingsModules =
        modules.getAll().stream()
            .filter(module -> !module.isToggleable())
            .sorted(Comparator.comparing(Module::getName))
            .toList();
    if (settingsModules.isEmpty()) {
      ImGui.textDisabled("No internal settings");
      return;
    }
    for (Module module : settingsModules) {
      boolean expanded = moduleExpanded.getOrDefault(module, false);
      if (ControlKit.categoryHeader(
          "Internal" + module.getId(),
          module.getName(),
          (int) module.getSettings().stream().filter(Setting::isVisible).count(),
          expanded)) {
        expanded = !expanded;
        moduleExpanded.put(module, expanded);
      }
      if (!expanded) continue;
      ImGui.indent(14);
      ImGui.textDisabled(module.getDescription());
      drawNativeSettings(module, false);
      boolean hasAdvanced =
          module.getSettings().stream()
              .anyMatch(
                  setting ->
                      setting.isVisible()
                          && setting.isAdvanced()
                          && setting != module.getKeybind()
                          && setting != module.getKeybindMode());
      if (hasAdvanced) {
        boolean showAdvanced = advancedExpanded.getOrDefault(module, false);
        if (ControlKit.disclosure(
            "SettingsAdvanced" + module.getId(), "Advanced settings", showAdvanced)) {
          showAdvanced = !showAdvanced;
          advancedExpanded.put(module, showAdvanced);
        }
        if (showAdvanced) {
          ImGui.indent(12);
          drawNativeSettings(module, true);
          ImGui.unindent(12);
        }
      }
      ImGui.unindent(14);
      ImGui.dummy(0, 4);
    }
  }

  boolean isCapturingKey() {
    return binding != null;
  }

  boolean cancelKeyCapture() {
    if (binding == null) return false;
    binding.getKeybind().setValue(KeySetting.UNBOUND);
    binding = null;
    return true;
  }

  void clearKeyCapture() {
    binding = null;
  }

  void drawScriptModule(ScriptModule module) {
    drawModule(module);
  }

  void captureKey(boolean menuOpen) {
    if (!menuOpen || binding == null) return;
    long window = minecraft.getWindow().handle();
    for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
      if (GLFW.glfwGetKey(window, key) != GLFW.GLFW_PRESS) continue;
      binding.getKeybind().setValue(key);
      binding = null;
      return;
    }
  }

  private void drawModule(Module module) {
    boolean expanded = moduleExpanded.getOrDefault(module, false);
    ControlKit.ModuleAction action =
        ControlKit.moduleRow(
            module.getId(),
            module.getName(),
            module.getDescription(),
            module.isEnabled(),
            expanded);
    if (action.toggle()) {
      module.toggle();
    }
    if (action.expand()) {
      expanded = !expanded;
      moduleExpanded.put(module, expanded);
    }
    if (!expanded) return;
    ImGui.indent(16);
    ImGui.dummy(0, 3);
    ImGui.textDisabled("SETTINGS  ·  Right-click the module row to close");
    ImGui.dummy(0, 2);
    if (module instanceof InventoryManagerModule inventoryManager) {
      drawKeybind(module, module.getKeybind());
      inventoryManagerView.draw(inventoryManager);
    } else if (!(module instanceof ScriptModule)) {
      drawKeybind(module, module.getKeybind());
      drawNativeSettings(module, false);
      boolean hasAdvanced =
          module.getSettings().stream()
              .anyMatch(
                  setting ->
                      setting.isVisible()
                          && setting.isAdvanced()
                          && setting != module.getKeybind()
                          && setting != module.getKeybindMode());
      if (hasAdvanced) {
        boolean showAdvanced = advancedExpanded.getOrDefault(module, false);
        if (ControlKit.disclosure(
            "ModuleAdvanced" + module.getId(), "Advanced settings", showAdvanced)) {
          showAdvanced = !showAdvanced;
          advancedExpanded.put(module, showAdvanced);
        }
        if (showAdvanced) {
          ImGui.indent(12);
          drawNativeSettings(module, true);
          ImGui.unindent(12);
        }
      }
    } else {
      boolean showAdvanced = true;
      if (module instanceof ScriptModule scriptModule
          && module.getSettings().stream()
              .filter(ScriptSetting.class::isInstance)
              .map(ScriptSetting.class::cast)
              .anyMatch(ScriptSetting::advanced)) {
        showAdvanced = scriptAdvanced.getOrDefault(scriptModule, false);
        if (ControlKit.toggle(
            "ShowAdvanced" + scriptModule.getId(), "Show advanced", showAdvanced, true)) {
          showAdvanced = !showAdvanced;
          scriptAdvanced.put(scriptModule, showAdvanced);
        }
      }
      for (Setting<?> setting : module.getSettings()) {
        if (!setting.isVisible()) continue;
        if (setting instanceof ScriptSetting scriptSetting
            && scriptSetting.advanced()
            && !showAdvanced) continue;
        if (setting instanceof KeySetting keybind) drawKeybind(module, keybind);
        else if (setting != module.getKeybindMode()) drawSetting(setting);
      }
    }
    if (module instanceof FriendsModule friends) drawFriends(friends);
    if (module instanceof RadioModule radio) {
      ImGui.textDisabled("Status: " + radio.getPlaybackStatus());
    }
    ImGui.unindent(16);
  }

  private void drawNativeSettings(Module module, boolean advanced) {
    for (Setting<?> setting : module.getSettings()) {
      if (!setting.isVisible()
          || setting.isAdvanced() != advanced
          || setting == module.getKeybind()
          || setting == module.getKeybindMode()) continue;
      drawSetting(setting);
    }
  }

  private void drawFriends(FriendsModule module) {
    var friends = module.getFriends();
    ImGui.separator();
    ImGui.text("Friends (" + friends.size() + ")");
    ImGui.setNextItemWidth(260);
    ImGui.inputTextWithHint("##AddFriend", "Online username or UUID", friendInput);
    ImGui.sameLine();
    if (ImGui.button("Add##Friend") && module.addFriend(friendInput.get())) friendInput.clear();
    if (ImGui.beginChild("##FriendList", 0, 180, true)) {
      if (friends.isEmpty()) ImGui.textDisabled("No friends");
      for (var friend : friends) {
        ImGui.text(friend.name());
        ImGui.sameLine();
        if (ImGui.button("Remove##Friend" + friend.uuid())) {
          module.removeFriend(friend.uuid());
        }
      }
    }
    ImGui.endChild();
    if (friends.isEmpty()) return;
    String popup = "Clear Friends##Confirm";
    if (ImGui.button("Clear All Friends")) ImGui.openPopup(popup);
    if (!ImGui.beginPopup(popup)) return;
    ImGui.text("Remove every friend?");
    if (ImGui.button("Clear##ConfirmFriends")) {
      module.clearFriends();
      ImGui.closeCurrentPopup();
    }
    ImGui.sameLine();
    if (ImGui.button("Cancel##ConfirmFriends")) ImGui.closeCurrentPopup();
    ImGui.endPopup();
  }

  private void drawSetting(Setting<?> setting) {
    String label = setting.getName() + "##" + Integer.toHexString(System.identityHashCode(setting));
    if (setting instanceof ScriptSetting value) {
      drawScriptSetting(value);
    } else if (setting instanceof BoolSetting value) {
      if (ControlKit.toggle(label, value.getName(), value.getValue(), true)) value.toggle();
    } else if (setting instanceof ColorSetting value) {
      drawColorSetting(value);
    } else if (setting instanceof ModeSetting<?> value) {
      drawEnumSetting(label, value);
    } else if (setting instanceof RangeSetting<?> value) {
      drawRangeSetting(label, value);
    } else if (setting instanceof NumberSetting<?> value) {
      drawNumberSetting(label, value);
    }
    drawSettingHelp(setting);
  }

  private void drawSettingHelp(Setting<?> setting) {
    if (setting.getDescription() == null || setting.getDescription().isBlank()) return;
    if (ImGui.isItemHovered()) ImGui.setTooltip(setting.getDescription());
  }

  private void drawScriptSetting(ScriptSetting setting) {
    if (setting.kind() == ScriptSettingKind.SECTION) {
      ImGui.separatorText(setting.getName());
      return;
    }
    if (setting.kind() == ScriptSettingKind.GROUP) {
      ImGui.textDisabled(setting.getName());
      return;
    }
    if (!setting.isEnabled()) ImGui.beginDisabled();
    String label = setting.getName() + "##ScriptSetting" + System.identityHashCode(setting);
    try {
      switch (setting.kind()) {
        case BOOLEAN -> {
          boolean value = setting.getValue().getAsBoolean();
          if (ControlKit.toggle(label, setting.getName(), value, setting.isEnabled())) {
            setting.setValue(gson.toJsonTree(!value));
          }
        }
        case INTEGER, KEYBIND -> {
          int current = setting.getValue().getAsInt();
          double updated =
              ControlKit.slider(
                  label,
                  setting.getName(),
                  Integer.toString(current),
                  current,
                  setting.minimum(),
                  setting.maximum(),
                  setting.isEnabled());
          int quantized =
              (int)
                  Math.round(
                      setting.minimum()
                          + Math.rint((updated - setting.minimum()) / setting.step())
                              * setting.step());
          if (quantized != current) {
            setting.setValue(gson.toJsonTree(quantized));
          }
        }
        case NUMBER -> {
          double current = setting.getValue().getAsDouble();
          double updated =
              ControlKit.slider(
                  label,
                  setting.getName(),
                  String.format("%.2f", current),
                  current,
                  setting.minimum(),
                  setting.maximum(),
                  setting.isEnabled());
          double quantized =
              setting.minimum()
                  + Math.rint((updated - setting.minimum()) / setting.step()) * setting.step();
          if (Double.compare(quantized, current) != 0) {
            setting.setValue(gson.toJsonTree(quantized));
          }
        }
        case RANGE -> drawScriptRange(label, setting);
        case ENUM -> drawScriptEnum(label, setting);
        case COLOR -> drawScriptColor(setting);
        case STRING, ITEM, BLOCK -> drawScriptText(label, setting, false);
        case MULTILINE, LIST, SET -> drawScriptText(label, setting, true);
        default -> {}
      }
    } catch (RuntimeException ignored) {
      // Keep the last valid setting value while the user edits structured text.
    } finally {
      if (!setting.isEnabled()) ImGui.endDisabled();
    }
  }

  private void drawScriptText(String label, ScriptSetting setting, boolean multiline) {
    String serialized =
        setting.kind() == ScriptSettingKind.LIST || setting.kind() == ScriptSettingKind.SET
            ? gson.toJson(setting.getValue())
            : setting.getValue().getAsString();
    ImString input =
        scriptInputs.computeIfAbsent(setting, ignored -> new ImString(serialized, 16_384));
    boolean changed =
        multiline ? ImGui.inputTextMultiline(label, input, -1, 90) : ImGui.inputText(label, input);
    if (!changed) return;
    if (setting.kind() == ScriptSettingKind.LIST || setting.kind() == ScriptSettingKind.SET) {
      var parsed = JsonParser.parseString(input.get());
      if (parsed.isJsonArray()) setting.setValue(parsed);
    } else {
      setting.setValue(gson.toJsonTree(input.get()));
    }
  }

  private void drawScriptRange(String label, ScriptSetting setting) {
    var range = setting.getValue().getAsJsonObject();
    double minimum = range.get("minimum").getAsDouble();
    double maximum = range.get("maximum").getAsDouble();
    double lower =
        ControlKit.slider(
            label + "Minimum",
            setting.getName() + " minimum",
            String.format("%.2f", minimum),
            minimum,
            setting.minimum(),
            maximum,
            setting.isEnabled());
    double upper =
        ControlKit.slider(
            label + "Maximum",
            setting.getName() + " maximum",
            String.format("%.2f", maximum),
            maximum,
            lower,
            setting.maximum(),
            setting.isEnabled());
    lower =
        setting.minimum()
            + Math.rint((lower - setting.minimum()) / setting.step()) * setting.step();
    upper =
        setting.minimum()
            + Math.rint((upper - setting.minimum()) / setting.step()) * setting.step();
    if (Double.compare(lower, minimum) == 0 && Double.compare(upper, maximum) == 0) return;
    var updated = new com.google.gson.JsonObject();
    updated.addProperty("minimum", lower);
    updated.addProperty("maximum", upper);
    setting.setValue(updated);
  }

  private void drawScriptEnum(String label, ScriptSetting setting) {
    String current = setting.getValue().getAsString();
    ImGui.text(setting.getName());
    ImGui.sameLine();
    ImGui.setNextItemWidth(210);
    if (!ImGui.beginCombo("##" + label, current)) return;
    for (String mode : setting.modes()) {
      boolean selected = current.equals(mode);
      if (ImGui.selectable(mode, selected)) setting.setValue(gson.toJsonTree(mode));
      if (selected) ImGui.setItemDefaultFocus();
    }
    ImGui.endCombo();
  }

  private void drawScriptColor(ScriptSetting setting) {
    java.awt.Color color = new java.awt.Color(setting.getValue().getAsInt(), true);
    float[] rgba = {
      color.getRed() / 255f,
      color.getGreen() / 255f,
      color.getBlue() / 255f,
      color.getAlpha() / 255f
    };
    if (ImGui.colorEdit4(setting.getName(), rgba)) {
      setting.setValue(
          gson.toJsonTree(new java.awt.Color(rgba[0], rgba[1], rgba[2], rgba[3]).getRGB()));
    }
  }

  private void drawEnumSetting(String label, ModeSetting<?> setting) {
    Enum<?>[] values = setting.getValues();
    ImGui.text(setting.getName());
    ImGui.sameLine();
    ImGui.setNextItemWidth(210);
    if (!ImGui.beginCombo("##" + label, enumName(setting.getValue()))) return;
    for (int index = 0; index < values.length; index++) {
      boolean selected = setting.getValue().ordinal() == index;
      if (ImGui.selectable(enumName(values[index]), selected)) setting.setIndex(index);
      if (selected) ImGui.setItemDefaultFocus();
    }
    ImGui.endCombo();
  }

  private void drawKeybind(Module module, KeySetting keybind) {
    String id = module.getClass().getName();
    ImGui.text("Keybind:");
    ImGui.sameLine();
    ImGui.textDisabled(binding == module ? "Press a key..." : keybind.displayName());
    ImGui.sameLine();
    if (ImGui.button("Set Key##SetBind" + id)) {
      binding = module;
    }
    if (keybind.isBound()) {
      ImGui.sameLine();
      if (ImGui.button("Clear##ClearBind" + id)) {
        keybind.setValue(KeySetting.UNBOUND);
      }
    }
    BindMode mode = module.getKeybindMode().getValue();
    ImGui.text("Activation mode");
    ImGui.sameLine();
    ImGui.setNextItemWidth(150);
    if (ImGui.beginCombo("##BindMode" + id, displayName(mode.name()))) {
      for (BindMode value : BindMode.values()) {
        boolean selected = mode == value;
        if (ImGui.selectable(displayName(value.name()), selected)) {
          module.getKeybindMode().setValue(value);
        }
        if (selected) ImGui.setItemDefaultFocus();
      }
      ImGui.endCombo();
    }
  }

  private void drawNumberSetting(String label, NumberSetting<?> setting) {
    Number value = setting.getValue();
    boolean integer = isInteger(value);
    String displayed =
        (integer ? Long.toString(value.longValue()) : String.format("%.2f", value.doubleValue()))
            + unitSuffix(setting);
    double updated =
        ControlKit.slider(
            label,
            setting.getName(),
            displayed,
            value.doubleValue(),
            setting.getMin().doubleValue(),
            setting.getMax().doubleValue(),
            true);
    if (Double.compare(updated, value.doubleValue()) != 0) setting.setDouble(updated);
  }

  private void drawRangeSetting(String label, RangeSetting<?> setting) {
    Number minimum = setting.getValue().minimum();
    Number maximum = setting.getValue().maximum();
    boolean integer = isInteger(minimum);
    String lowerValue =
        (integer
                ? Long.toString(minimum.longValue())
                : String.format("%.2f", minimum.doubleValue()))
            + unitSuffix(setting);
    String upperValue =
        (integer
                ? Long.toString(maximum.longValue())
                : String.format("%.2f", maximum.doubleValue()))
            + unitSuffix(setting);
    double lower =
        ControlKit.slider(
            "Minimum" + label,
            setting.getName() + " minimum",
            lowerValue,
            minimum.doubleValue(),
            setting.getMin().doubleValue(),
            maximum.doubleValue(),
            true);
    double upper =
        ControlKit.slider(
            "Maximum" + label,
            setting.getName() + " maximum",
            upperValue,
            maximum.doubleValue(),
            lower,
            setting.getMax().doubleValue(),
            true);
    if (Double.compare(lower, minimum.doubleValue()) != 0
        || Double.compare(upper, maximum.doubleValue()) != 0) setting.setDouble(lower, upper);
  }

  private static boolean isInteger(Number value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long;
  }

  private static String unitSuffix(Setting<?> setting) {
    return setting.getUnit() == null || setting.getUnit().isBlank() ? "" : " " + setting.getUnit();
  }

  private static String integerFormat(Setting<?> setting) {
    return formatWithUnit("%d", setting.getUnit());
  }

  private static String decimalFormat(Setting<?> setting) {
    return formatWithUnit("%.2f", setting.getUnit());
  }

  private static String formatWithUnit(String format, String unit) {
    return unit == null || unit.isBlank() ? format : format + " " + unit.replace("%", "%%");
  }

  private void drawColorSetting(ColorSetting setting) {
    String id = Integer.toHexString(System.identityHashCode(setting));
    String popup = "ColorPicker##" + id;
    Color color = setting.getValue();
    float[] rgba = {
      color.getRed() / 255f,
      color.getGreen() / 255f,
      color.getBlue() / 255f,
      color.getAlpha() / 255f
    };
    ImGui.text(setting.getName());
    ImGui.sameLine();
    int buttonFlags = ImGuiColorEditFlags.NoTooltip | ImGuiColorEditFlags.NoDragDrop;
    if (ImGui.colorButton("##Color" + id, rgba, buttonFlags, 16, 16)) {
      ImGui.openPopup(popup);
    }
    if (!ImGui.beginPopup(popup)) return;
    int pickerFlags =
        ImGuiColorEditFlags.NoSidePreview
            | ImGuiColorEditFlags.NoSmallPreview
            | ImGuiColorEditFlags.NoInputs
            | ImGuiColorEditFlags.NoLabel
            | ImGuiColorEditFlags.AlphaBar;
    if (ImGui.colorPicker4("##Picker" + id, rgba, pickerFlags)) {
      setting.setValue(new Color(rgba[0], rgba[1], rgba[2], rgba[3]));
    }
    ImGui.endPopup();
  }

  private static String enumName(Enum<?> value) {
    return value.toString().equals(value.name()) ? displayName(value.name()) : value.toString();
  }

  private static String displayName(String value) {
    String[] words = value.toLowerCase().split("_");
    StringBuilder formatted = new StringBuilder();
    for (String word : words) {
      if (!formatted.isEmpty()) formatted.append(' ');
      formatted.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return formatted.toString();
  }
}
