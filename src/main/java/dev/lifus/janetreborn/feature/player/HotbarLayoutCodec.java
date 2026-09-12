package dev.lifus.janetreborn.feature.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lifus.janetreborn.config.ConfigNode;
import java.util.ArrayList;
import java.util.List;

public final class HotbarLayoutCodec {
  public static final int VERSION = 2;
  public static final int SLOT_COUNT = 10;

  private HotbarLayoutCodec() {}

  public static void write(ConfigNode config, List<HotbarSlotConfig> slots) {
    JsonArray encodedSlots = new JsonArray();
    for (int index = 0; index < SLOT_COUNT; index++) {
      HotbarSlotConfig slot = index < slots.size() ? slots.get(index) : HotbarSlotConfig.ignored();
      JsonObject encodedSlot = new JsonObject();
      encodedSlot.addProperty("mode", slot.mode().name());
      JsonArray preferences = new JsonArray();
      for (ItemSelector selector : slot.preferences()) {
        JsonObject encodedSelector = new JsonObject();
        encodedSelector.addProperty("type", selector.type().name());
        encodedSelector.addProperty("value", selector.value());
        preferences.add(encodedSelector);
      }
      encodedSlot.add("preferences", preferences);
      encodedSlot.addProperty("fallback", slot.fallback().name());
      encodedSlots.add(encodedSlot);
    }
    config.put("hotbarLayoutVersion", VERSION);
    config.put("hotbarSlots", encodedSlots);
  }

  public static Result read(ConfigNode config, List<HotbarSlotConfig> defaults) {
    List<HotbarSlotConfig> slots = copyDefaults(defaults);
    int version = config.number("hotbarLayoutVersion").map(Double::intValue).orElse(0);
    java.util.Optional<JsonElement> encodedSlots =
        config.element("hotbarSlots").filter(JsonElement::isJsonArray);
    if (version >= VERSION && encodedSlots.isPresent()) {
      readStructured(encodedSlots.orElseThrow().getAsJsonArray(), slots);
      return new Result(slots, false);
    }
    List<String> legacy = config.strings("hotbar");
    readLegacy(legacy, slots);
    return new Result(slots, !legacy.isEmpty());
  }

  private static void readStructured(JsonArray encoded, List<HotbarSlotConfig> slots) {
    for (int index = 0; index < Math.min(SLOT_COUNT, encoded.size()); index++) {
      JsonElement element = encoded.get(index);
      if (!element.isJsonObject()) continue;
      JsonObject object = element.getAsJsonObject();
      HotbarSlotConfig baseline = slots.get(index);
      SlotMode mode = enumValue(object.get("mode"), SlotMode.class, baseline.mode());
      FallbackPolicy fallback =
          enumValue(object.get("fallback"), FallbackPolicy.class, baseline.fallback());
      List<ItemSelector> preferences = new ArrayList<>();
      JsonElement encodedPreferences = object.get("preferences");
      if (encodedPreferences != null && encodedPreferences.isJsonArray()) {
        for (JsonElement encodedSelector : encodedPreferences.getAsJsonArray()) {
          decodeSelector(encodedSelector).ifPresent(preferences::add);
        }
      }
      slots.set(index, new HotbarSlotConfig(mode, preferences, fallback));
    }
  }

  private static java.util.Optional<ItemSelector> decodeSelector(JsonElement encoded) {
    if (!encoded.isJsonObject()) return java.util.Optional.empty();
    JsonObject object = encoded.getAsJsonObject();
    JsonElement type = object.get("type");
    JsonElement value = object.get("value");
    if (type == null || value == null || !type.isJsonPrimitive() || !value.isJsonPrimitive())
      return java.util.Optional.empty();
    try {
      return switch (ItemSelector.Type.valueOf(type.getAsString())) {
        case EXACT_ITEM -> ItemSelector.exact(value.getAsString());
        case CATEGORY -> ItemSelector.category(value.getAsString());
      };
    } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
      return java.util.Optional.empty();
    }
  }

  private static void readLegacy(List<String> encoded, List<HotbarSlotConfig> slots) {
    for (int index = 0; index < Math.min(SLOT_COUNT, encoded.size()); index++) {
      try {
        HotbarCategory category = HotbarCategory.valueOf(encoded.get(index));
        slots.set(
            index,
            switch (category) {
              case IGNORE -> HotbarSlotConfig.ignored();
              case NONE -> HotbarSlotConfig.empty();
              default -> HotbarSlotConfig.managed(ItemSelector.category(category));
            });
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  private static List<HotbarSlotConfig> copyDefaults(List<HotbarSlotConfig> defaults) {
    List<HotbarSlotConfig> result = new ArrayList<>(SLOT_COUNT);
    for (int index = 0; index < SLOT_COUNT; index++) {
      result.add(index < defaults.size() ? defaults.get(index).copy() : HotbarSlotConfig.ignored());
    }
    return result;
  }

  private static <E extends Enum<E>> E enumValue(JsonElement value, Class<E> type, E defaultValue) {
    if (value == null || !value.isJsonPrimitive()) return defaultValue;
    try {
      return Enum.valueOf(type, value.getAsString());
    } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
      return defaultValue;
    }
  }

  public record Result(List<HotbarSlotConfig> slots, boolean migrated) {}
}
