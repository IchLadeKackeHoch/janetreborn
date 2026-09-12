package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import opsec.misuyaka.sdk.setting.Setting;

public final class ScriptSetting extends Setting<JsonElement> {
  private final ScriptSettingKind kind;
  private final double minimum;
  private final double maximum;
  private final double step;
  private final List<String> modes;
  private final String section;
  private final boolean advanced;
  private UnaryOperator<JsonElement> customNormalizer = UnaryOperator.identity();
  private BiConsumer<JsonElement, JsonElement> changeListener = (before, after) -> {};
  private BooleanSupplier enabled = () -> true;
  private UnaryOperator<JsonElement> serializer = UnaryOperator.identity();
  private UnaryOperator<JsonElement> deserializer = UnaryOperator.identity();

  public ScriptSetting(
      String id,
      String name,
      ScriptSettingKind kind,
      JsonElement defaultValue,
      double minimum,
      double maximum,
      double step,
      List<String> modes,
      String section,
      boolean advanced) {
    super(id, name, normalize(kind, defaultValue, minimum, maximum, modes));
    this.kind = Objects.requireNonNull(kind, "kind");
    this.minimum = minimum;
    this.maximum = maximum;
    this.step = step;
    this.modes = List.copyOf(modes);
    this.section = section == null ? "" : section;
    this.advanced = advanced;
    if (minimum > maximum || step <= 0)
      throw new IllegalArgumentException("Invalid setting bounds");
  }

  @Override
  public void setValue(JsonElement value) {
    JsonElement before = getValue().deepCopy();
    JsonElement normalized = normalize(kind, value, minimum, maximum, modes);
    normalized =
        Objects.requireNonNull(customNormalizer.apply(normalized.deepCopy()), "normalized value");
    normalized = normalize(kind, normalized, minimum, maximum, modes);
    super.setValue(normalized.deepCopy());
    if (!before.equals(normalized)) changeListener.accept(before, normalized.deepCopy());
  }

  @Override
  public void reset() {
    setValue(getDefaultValue().deepCopy());
  }

  public ScriptSetting normalizer(UnaryOperator<JsonElement> normalizer) {
    customNormalizer = Objects.requireNonNull(normalizer, "normalizer");
    return this;
  }

  public ScriptSetting onChange(BiConsumer<JsonElement, JsonElement> listener) {
    changeListener = Objects.requireNonNull(listener, "listener");
    return this;
  }

  public ScriptSetting serializer(UnaryOperator<JsonElement> serializer) {
    this.serializer = Objects.requireNonNull(serializer, "serializer");
    return this;
  }

  public ScriptSetting deserializer(UnaryOperator<JsonElement> deserializer) {
    this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
    return this;
  }

  public JsonElement serializeValue() {
    return Objects.requireNonNull(serializer.apply(getValue().deepCopy()), "serialized value");
  }

  public void deserializeValue(JsonElement value) {
    setValue(Objects.requireNonNull(deserializer.apply(value.deepCopy()), "deserialized value"));
  }

  public ScriptSetting enabledWhen(BooleanSupplier condition) {
    enabled = Objects.requireNonNull(condition, "condition");
    return this;
  }

  public boolean isEnabled() {
    return enabled.getAsBoolean();
  }

  public ScriptSettingKind kind() {
    return kind;
  }

  public double minimum() {
    return minimum;
  }

  public double maximum() {
    return maximum;
  }

  public double step() {
    return step;
  }

  public List<String> modes() {
    return modes;
  }

  public String section() {
    return section;
  }

  public boolean advanced() {
    return advanced;
  }

  private static JsonElement normalize(
      ScriptSettingKind kind,
      JsonElement input,
      double minimum,
      double maximum,
      List<String> modes) {
    Objects.requireNonNull(kind, "kind");
    JsonElement value = Objects.requireNonNull(input, "value");
    if (value.isJsonNull()
        && kind != ScriptSettingKind.GROUP
        && kind != ScriptSettingKind.SECTION) {
      throw new IllegalArgumentException("Setting value cannot be nil");
    }
    return switch (kind) {
      case BOOLEAN -> new JsonPrimitive(value.getAsBoolean());
      case INTEGER, KEYBIND ->
          new JsonPrimitive((long) Math.clamp(value.getAsLong(), minimum, maximum));
      case NUMBER -> new JsonPrimitive(Math.clamp(value.getAsDouble(), minimum, maximum));
      case RANGE -> normalizeRange(value, minimum, maximum);
      case STRING, MULTILINE -> {
        String text = value.getAsString();
        if (text.length() > 16_384)
          throw new IllegalArgumentException("String setting is too long");
        yield new JsonPrimitive(text);
      }
      case ITEM -> new JsonPrimitive(registryId(value.getAsString(), true));
      case BLOCK -> new JsonPrimitive(registryId(value.getAsString(), false));
      case ENUM -> {
        String mode = value.getAsString();
        if (!modes.contains(mode))
          throw new IllegalArgumentException("Unknown setting mode: " + mode);
        yield new JsonPrimitive(mode);
      }
      case COLOR -> normalizeColor(value);
      case LIST -> normalizeList(value, false);
      case SET -> normalizeList(value, true);
      case GROUP, SECTION -> JsonNull.INSTANCE;
    };
  }

  private static JsonElement normalizeRange(JsonElement value, double minimum, double maximum) {
    JsonObject range = value.getAsJsonObject();
    double low = Math.clamp(range.get("minimum").getAsDouble(), minimum, maximum);
    double high = Math.clamp(range.get("maximum").getAsDouble(), minimum, maximum);
    if (low > high) {
      double swap = low;
      low = high;
      high = swap;
    }
    JsonObject normalized = new JsonObject();
    normalized.addProperty("minimum", low);
    normalized.addProperty("maximum", high);
    return normalized;
  }

  private static JsonElement normalizeColor(JsonElement value) {
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
      String hex = value.getAsString().replace("#", "");
      if (hex.length() == 6) hex = "FF" + hex;
      if (hex.length() != 8)
        throw new IllegalArgumentException("Color must use RRGGBB or AARRGGBB");
      return new JsonPrimitive((int) Long.parseUnsignedLong(hex, 16));
    }
    return new JsonPrimitive(value.getAsInt());
  }

  private static JsonElement normalizeList(JsonElement value, boolean unique) {
    JsonArray input = value.getAsJsonArray();
    if (input.size() > 256) throw new IllegalArgumentException("List setting exceeds 256 values");
    JsonArray normalized = new JsonArray();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (JsonElement element : input) {
      if (element.isJsonObject() || element.isJsonArray()) {
        throw new IllegalArgumentException("List settings contain JSON primitives only");
      }
      if (!unique || seen.add(element.toString())) normalized.add(element.deepCopy());
    }
    return normalized;
  }

  private static String registryId(String value, boolean item) {
    Identifier id = Identifier.tryParse(value);
    if (id == null
        || item && BuiltInRegistries.ITEM.get(id).isEmpty()
        || !item && BuiltInRegistries.BLOCK.get(id).isEmpty()) {
      throw new IllegalArgumentException("Unknown " + (item ? "item" : "block") + " id: " + value);
    }
    return id.toString();
  }
}
