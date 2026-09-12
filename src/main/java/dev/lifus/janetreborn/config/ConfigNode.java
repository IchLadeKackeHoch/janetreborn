package dev.lifus.janetreborn.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ConfigNode {
  private final JsonObject data;

  public ConfigNode(JsonObject data) {
    this.data = Objects.requireNonNull(data, "data");
  }

  public void put(String key, String value) {
    data.addProperty(key, value);
  }

  public void put(String key, boolean value) {
    data.addProperty(key, value);
  }

  public void put(String key, Number value) {
    data.addProperty(key, value);
  }

  public void putStrings(String key, List<String> values) {
    JsonArray encoded = new JsonArray();
    values.forEach(encoded::add);
    data.add(key, encoded);
  }

  public void put(String key, JsonElement value) {
    data.add(key, Objects.requireNonNull(value, "value"));
  }

  public Optional<JsonElement> element(String key) {
    JsonElement value = data.get(key);
    return value == null || value.isJsonNull() ? Optional.empty() : Optional.of(value);
  }

  public Optional<String> string(String key) {
    JsonElement value = data.get(key);
    if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return Optional.empty();
    try {
      return Optional.of(value.getAsString());
    } catch (UnsupportedOperationException | NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  public Optional<Boolean> booleanValue(String key) {
    JsonElement value = data.get(key);
    return value == null || value.isJsonNull()
        ? Optional.empty()
        : Optional.of(value.getAsBoolean());
  }

  public Optional<Double> number(String key) {
    JsonElement value = data.get(key);
    if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return Optional.empty();
    try {
      return Optional.of(value.getAsDouble());
    } catch (UnsupportedOperationException | NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  public List<String> strings(String key) {
    JsonElement value = data.get(key);
    if (value == null || !value.isJsonArray()) return List.of();
    return value.getAsJsonArray().asList().stream()
        .map(element -> element.isJsonPrimitive() ? element.getAsString() : "")
        .toList();
  }
}
