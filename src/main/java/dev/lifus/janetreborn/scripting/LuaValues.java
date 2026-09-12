package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import party.iroiro.luajava.Lua;

final class LuaValues {
  private static final int MAX_DEPTH = 32;
  private static final int MAX_NODES = 10_000;

  private LuaValues() {}

  static void push(Lua lua, JsonElement value) {
    push(lua, value, 0, new AtomicInteger());
  }

  static JsonElement read(Lua lua, int index) {
    return read(lua, index, 0, new AtomicInteger());
  }

  private static void push(Lua lua, JsonElement value, int depth, AtomicInteger nodes) {
    check(depth, nodes);
    if (value == null || value.isJsonNull()) {
      lua.pushNil();
    } else if (value.isJsonPrimitive()) {
      JsonPrimitive primitive = value.getAsJsonPrimitive();
      if (primitive.isBoolean()) lua.push(primitive.getAsBoolean());
      else if (primitive.isNumber()) lua.push(primitive.getAsNumber());
      else lua.push(primitive.getAsString());
    } else if (value.isJsonArray()) {
      JsonArray array = value.getAsJsonArray();
      lua.createTable(array.size(), 0);
      for (int index = 0; index < array.size(); index++) {
        push(lua, array.get(index), depth + 1, nodes);
        lua.rawSetI(-2, index + 1);
      }
    } else {
      JsonObject object = value.getAsJsonObject();
      lua.createTable(0, object.size());
      for (var entry : object.entrySet()) {
        push(lua, entry.getValue(), depth + 1, nodes);
        lua.setField(-2, entry.getKey());
      }
    }
  }

  private static JsonElement read(Lua lua, int index, int depth, AtomicInteger nodes) {
    check(depth, nodes);
    if (lua.isNoneOrNil(index)) return JsonNull.INSTANCE;
    if (lua.isBoolean(index)) return new JsonPrimitive(lua.toBoolean(index));
    if (lua.isNumber(index)) {
      return lua.isInteger(index)
          ? new JsonPrimitive(lua.toInteger(index))
          : new JsonPrimitive(lua.toNumber(index));
    }
    if (lua.isString(index)) return new JsonPrimitive(lua.toString(index));
    if (!lua.isTable(index)) {
      throw new IllegalArgumentException(
          "Only JSON-compatible Lua values may cross the SDK boundary");
    }
    int absolute = index < 0 ? lua.getTop() + index + 1 : index;
    int arrayLength = lua.rawLength(absolute);
    if (arrayLength > 0) {
      JsonArray result = new JsonArray();
      for (int arrayIndex = 1; arrayIndex <= arrayLength; arrayIndex++) {
        lua.rawGetI(absolute, arrayIndex);
        try {
          result.add(read(lua, -1, depth + 1, nodes));
        } finally {
          lua.pop(1);
        }
      }
      return result;
    }
    List<JsonElement> array = new ArrayList<>();
    JsonObject object = new JsonObject();
    boolean arrayOnly = true;
    lua.pushNil();
    while (lua.next(absolute) != 0) {
      JsonElement converted = read(lua, -1, depth + 1, nodes);
      if (lua.isInteger(-2) && lua.toInteger(-2) > 0 && lua.toInteger(-2) <= Integer.MAX_VALUE) {
        int key = (int) lua.toInteger(-2);
        while (array.size() < key) array.add(JsonNull.INSTANCE);
        array.set(key - 1, converted);
      } else if (lua.isString(-2)) {
        arrayOnly = false;
        object.add(lua.toString(-2), converted);
      } else {
        lua.pop(1);
        throw new IllegalArgumentException(
            "Structured tables require string or positive integer keys");
      }
      lua.pop(1);
    }
    if (arrayOnly) {
      JsonArray result = new JsonArray();
      array.forEach(result::add);
      return result;
    }
    for (int arrayIndex = 0; arrayIndex < array.size(); arrayIndex++) {
      if (!array.get(arrayIndex).isJsonNull()) {
        object.add(Integer.toString(arrayIndex + 1), array.get(arrayIndex));
      }
    }
    return object;
  }

  private static void check(int depth, AtomicInteger nodes) {
    if (depth > MAX_DEPTH || nodes.incrementAndGet() > MAX_NODES) {
      throw new IllegalArgumentException("Structured Lua value exceeds SDK limits");
    }
  }
}
