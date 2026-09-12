package dev.lifus.janetreborn.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lifus.janetreborn.scripting.ScriptSetting;
import java.awt.Color;
import lombok.RequiredArgsConstructor;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.Modules;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;
import opsec.misuyaka.sdk.setting.KeySetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.RangeSetting;
import opsec.misuyaka.sdk.setting.Setting;

@RequiredArgsConstructor
final class ModuleCodec {
  private final Gson gson;
  private final Modules modules;
  private JsonObject retained = new JsonObject();

  JsonObject encode() {
    JsonObject root = retained.deepCopy();
    JsonObject encodedModules =
        root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
    for (Module module : modules.getAll()) {
      JsonObject data = new JsonObject();
      JsonObject settings = new JsonObject();
      data.addProperty("enabled", module.isEnabled());
      for (Setting<?> setting : module.getSettings()) {
        settings.add(setting.getId(), encode(setting));
      }
      data.add("settings", settings);
      module.writeConfig(new ConfigNode(data));
      encodedModules.add(module.getId(), data);
    }
    root.add("modules", encodedModules);
    return root;
  }

  boolean decode(JsonObject root) {
    retained = root.deepCopy();
    JsonObject encodedModules = root.getAsJsonObject("modules");
    if (encodedModules == null) return false;
    for (Module module : modules.getAll()) {
      JsonObject data = moduleData(encodedModules, module.getId());
      if (data == null) data = moduleData(encodedModules, module.getName());
      decode(module, data);
    }
    return true;
  }

  static JsonObject moduleData(JsonObject encodedModules, String moduleName) {
    JsonObject data = encodedModules.getAsJsonObject(moduleName);
    if (data == null && moduleName.equals("AutoWater")) {
      return encodedModules.getAsJsonObject("AutoFree");
    }
    return data;
  }

  private JsonElement encode(Setting<?> setting) {
    if (setting instanceof ScriptSetting scriptSetting) return scriptSetting.serializeValue();
    if (setting instanceof ColorSetting color) return gson.toJsonTree(color.getValue().getRGB());
    if (setting instanceof RangeSetting<?> range) {
      JsonObject encoded = new JsonObject();
      encoded.addProperty("minimum", range.getValue().minimum());
      encoded.addProperty("maximum", range.getValue().maximum());
      return encoded;
    }
    if (setting.getValue() instanceof Enum<?> value) return gson.toJsonTree(value.name());
    return gson.toJsonTree(setting.getValue());
  }

  private void decode(Module module, JsonObject data) {
    if (data == null) return;
    JsonObject settings = data.getAsJsonObject("settings");
    if (settings != null) {
      for (Setting<?> setting : module.getSettings()) {
        JsonElement value = settings.get(setting.getId());
        if (value == null) value = settings.get(setting.getName());
        try {
          if (value == null
              && setting instanceof RangeSetting<?> range
              && range.getLegacyMinimumId() != null
              && range.getLegacyMaximumId() != null) {
            decodeLegacyRange(range, settings);
          } else {
            decode(setting, value);
          }
        } catch (RuntimeException ignored) {
          // Malformed and obsolete values do not make the complete configuration unusable.
        }
      }
    }
    module.readConfig(new ConfigNode(data));
    if (data.has("enabled")) module.setEnabled(data.get("enabled").getAsBoolean());
  }

  private void decodeLegacyRange(RangeSetting<?> setting, JsonObject settings) {
    JsonElement minimum = settings.get(setting.getLegacyMinimumId());
    JsonElement maximum = settings.get(setting.getLegacyMaximumId());
    if (minimum == null || maximum == null) return;
    if (setting.isLegacyCenterVariation()) {
      double center = minimum.getAsDouble();
      double variation = maximum.getAsDouble();
      setting.setDouble(center - variation, center + variation);
    } else {
      setting.setDouble(minimum.getAsDouble(), maximum.getAsDouble());
    }
  }

  private void decode(Setting<?> setting, JsonElement value) {
    if (value == null || value.isJsonNull()) return;
    if (setting instanceof ScriptSetting scriptSetting) {
      scriptSetting.deserializeValue(value.deepCopy());
    } else if (setting instanceof BoolSetting booleanSetting) {
      booleanSetting.setValue(value.getAsBoolean());
    } else if (setting instanceof KeySetting keybindSetting) {
      keybindSetting.setValue(value.getAsInt());
    } else if (setting instanceof ColorSetting colorSetting) {
      colorSetting.setValue(new Color(value.getAsInt(), true));
    } else if (setting instanceof NumberSetting<?> numberSetting) {
      numberSetting.setDouble(value.getAsDouble());
    } else if (setting instanceof RangeSetting<?> rangeSetting) {
      JsonObject range = value.getAsJsonObject();
      rangeSetting.setDouble(
          range.get("minimum").getAsDouble(), range.get("maximum").getAsDouble());
    } else if (setting instanceof ModeSetting<?> enumSetting) {
      String name = value.getAsString();
      var values = enumSetting.getValues();
      for (int index = 0; index < values.length; index++) {
        if (values[index].name().equals(name)) enumSetting.setIndex(index);
      }
    }
  }
}
