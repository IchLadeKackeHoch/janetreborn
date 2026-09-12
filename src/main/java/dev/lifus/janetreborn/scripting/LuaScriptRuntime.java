package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.LuaException;
import party.iroiro.luajava.luaj.LuaJ;

public final class LuaScriptRuntime implements AutoCloseable {
  private static final Pattern LINE = Pattern.compile("(?::|\\[string \\\")([^:\\]\\\"]+):(\\d+):");
  private static final int HOOK_GRANULARITY = 1_000;
  private static final long MAX_SOURCE_BYTES = 1_048_576;
  private final ScriptPackage scriptPackage;
  private final Lua lua = new LuaJ();
  private final ExecutionGuard guard;
  private final Map<String, LuaRef> imports = new HashMap<>();
  private boolean closed;

  public LuaScriptRuntime(ScriptPackage scriptPackage, ScriptLimits limits) {
    this.scriptPackage = Objects.requireNonNull(scriptPackage, "scriptPackage");
    guard = new ExecutionGuard(Objects.requireNonNull(limits, "limits"));
    initializeSandbox();
  }

  public synchronized void executeEntryPoint() {
    executeFile(scriptPackage.entryPoint(), 0);
  }

  public synchronized void executeFile(Path file, int results) {
    requireOpen();
    Path owned = file.toAbsolutePath().normalize();
    if (!owned.startsWith(scriptPackage.root()))
      throw new SecurityException("Script file is outside package");
    try {
      long size = Files.size(owned);
      if (size > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Lua source exceeds 1 MiB");
      byte[] source = Files.readAllBytes(owned);
      int top = lua.getTop();
      try (ExecutionGuard.Frame ignored = guard.enter("load")) {
        lua.load(direct(source), "@" + owned);
        lua.pCall(0, results);
      } finally {
        if (results == 0) lua.setTop(top);
      }
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof ScriptRuntimeException runtimeException) throw runtimeException;
      throw convert(owned, exception);
    }
  }

  public synchronized void setGlobalFunction(String name, JFunction function) {
    requireOpen();
    lua.push(function);
    lua.setGlobal(name);
  }

  public synchronized void newTable() {
    requireOpen();
    lua.newTable();
  }

  public synchronized void setFieldFunction(int tableIndex, String name, JFunction function) {
    lua.push(function);
    lua.setField(tableIndex < 0 ? tableIndex - 1 : tableIndex, name);
  }

  public synchronized void setFieldValue(int tableIndex, String name, JsonElement value) {
    LuaValues.push(lua, value);
    lua.setField(tableIndex < 0 ? tableIndex - 1 : tableIndex, name);
  }

  public synchronized void setFieldTable(int tableIndex, String name) {
    lua.setField(tableIndex, name);
  }

  public synchronized void setGlobalFromTop(String name) {
    lua.setGlobal(name);
  }

  public synchronized LuaRef reference(int index) {
    requireOpen();
    lua.pushValue(index);
    return new LuaRef(this, lua.ref());
  }

  public synchronized LuaRef referenceFunction(int index) {
    if (!lua.isFunction(index)) throw new IllegalArgumentException("Expected a Lua function");
    return reference(index);
  }

  public synchronized JsonElement argument(int index) {
    return LuaValues.read(lua, index);
  }

  public synchronized String stringArgument(int index, String name) {
    if (!lua.isString(index)) throw new IllegalArgumentException(name + " must be a string");
    return lua.toString(index);
  }

  public synchronized long integerArgument(int index, String name) {
    if (!lua.isNumber(index)) throw new IllegalArgumentException(name + " must be an integer");
    double value = lua.toNumber(index);
    if (!Double.isFinite(value) || value != Math.rint(value)) {
      throw new IllegalArgumentException(name + " must be an integer");
    }
    return (long) value;
  }

  public synchronized boolean booleanArgument(int index, String name) {
    if (!lua.isBoolean(index)) throw new IllegalArgumentException(name + " must be a boolean");
    return lua.toBoolean(index);
  }

  public synchronized boolean isFunction(int index) {
    return lua.isFunction(index);
  }

  public synchronized boolean isTable(int index) {
    return lua.isTable(index);
  }

  public synchronized String displayArgument(int index) {
    if (lua.isNoneOrNil(index)) return "nil";
    if (lua.isBoolean(index)) return Boolean.toString(lua.toBoolean(index));
    if (lua.isString(index) || lua.isNumber(index)) return lua.toString(index);
    return "<" + lua.type(index).name().toLowerCase() + ">";
  }

  public synchronized boolean isNil(int index) {
    return lua.isNoneOrNil(index);
  }

  public synchronized JsonElement field(int tableIndex, String name, JsonElement defaultValue) {
    if (!lua.isTable(tableIndex)) throw new IllegalArgumentException("Expected an options table");
    int absolute = tableIndex < 0 ? lua.getTop() + tableIndex + 1 : tableIndex;
    lua.getField(absolute, name);
    try {
      return lua.isNoneOrNil(-1) ? defaultValue : LuaValues.read(lua, -1);
    } finally {
      lua.pop(1);
    }
  }

  public synchronized String stringField(int tableIndex, String name, String defaultValue) {
    JsonElement value = field(tableIndex, name, null);
    return value == null ? defaultValue : value.getAsString();
  }

  public synchronized boolean booleanField(int tableIndex, String name, boolean defaultValue) {
    JsonElement value = field(tableIndex, name, null);
    return value == null ? defaultValue : value.getAsBoolean();
  }

  public synchronized double numberField(int tableIndex, String name, double defaultValue) {
    JsonElement value = field(tableIndex, name, null);
    return value == null ? defaultValue : value.getAsDouble();
  }

  public synchronized LuaRef functionField(int tableIndex, String name) {
    if (!lua.isTable(tableIndex)) throw new IllegalArgumentException("Expected an options table");
    int absolute = tableIndex < 0 ? lua.getTop() + tableIndex + 1 : tableIndex;
    lua.getField(absolute, name);
    try {
      if (lua.isNoneOrNil(-1)) return null;
      return referenceFunction(-1);
    } finally {
      lua.pop(1);
    }
  }

  public synchronized LuaRef globalFunction(String name) {
    requireOpen();
    lua.getGlobal(name);
    try {
      if (lua.isNoneOrNil(-1)) return null;
      return referenceFunction(-1);
    } finally {
      lua.pop(1);
    }
  }

  public synchronized void pushReference(LuaRef reference) {
    requireOpen();
    lua.refGet(reference.reference());
  }

  public synchronized int top() {
    return lua.getTop();
  }

  public synchronized int returnValue(JsonElement value) {
    LuaValues.push(lua, value);
    return 1;
  }

  public synchronized int returnString(String value) {
    lua.push(value);
    return 1;
  }

  public synchronized int returnBoolean(boolean value) {
    lua.push(value);
    return 1;
  }

  public synchronized JsonElement invoke(LuaRef function, JsonElement... arguments) {
    requireOpen();
    int top = lua.getTop();
    try (ExecutionGuard.Frame ignored = guard.enter("callback")) {
      lua.refGet(function.reference());
      for (JsonElement argument : arguments) LuaValues.push(lua, argument);
      lua.pCall(arguments.length, 1);
      return LuaValues.read(lua, -1);
    } catch (RuntimeException exception) {
      throw convert(scriptPackage.entryPoint(), exception);
    } finally {
      lua.setTop(top);
    }
  }

  public synchronized boolean invokeMethod(LuaRef table, String method, JsonElement... arguments) {
    requireOpen();
    int top = lua.getTop();
    try (ExecutionGuard.Frame ignored = guard.enter(method)) {
      lua.refGet(table.reference());
      lua.getField(-1, method);
      if (lua.isNoneOrNil(-1)) return false;
      if (!lua.isFunction(-1)) throw new IllegalArgumentException(method + " must be a function");
      lua.pushValue(-2);
      lua.remove(-3);
      for (JsonElement argument : arguments) LuaValues.push(lua, argument);
      lua.pCall(arguments.length + 1, 0);
      return true;
    } catch (RuntimeException exception) {
      throw convert(scriptPackage.entryPoint(), exception);
    } finally {
      lua.setTop(top);
    }
  }

  public synchronized int importFromArgument(int index) {
    String requested = stringArgument(index, "import path");
    String normalized = requested.replace('.', '/').replace('\\', '/');
    if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
      throw new SecurityException("Import path is outside approved SDK locations");
    }
    if (!normalized.endsWith(".lua")) normalized += ".lua";
    LuaRef cached = imports.get(normalized);
    if (cached != null) {
      lua.refGet(cached.reference());
      return 1;
    }
    Path target = scriptPackage.resolveOwned(normalized);
    if (!Files.isRegularFile(target))
      throw new IllegalArgumentException("Import not found: " + requested);
    try {
      if (Files.size(target) > MAX_SOURCE_BYTES)
        throw new IllegalArgumentException("Imported source exceeds 1 MiB");
      lua.load(direct(Files.readAllBytes(target)), "@" + target);
      lua.pCall(0, 1);
      if (lua.isNil(-1)) {
        lua.pop(1);
        lua.push(true);
      }
      LuaRef reference = reference(-1);
      imports.put(normalized, reference);
      return 1;
    } catch (IOException | LuaException exception) {
      throw convert(target, exception);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (LuaRef reference : imports.values()) reference.close();
    imports.clear();
    lua.close();
  }

  synchronized void release(int reference) {
    if (!closed) lua.unref(reference);
  }

  private void initializeSandbox() {
    lua.openLibraries();
    setGlobalFunction(
        "__janet_budget_hook",
        state -> {
          guard.check(HOOK_GRANULARITY);
          return 0;
        });
    try {
      lua.run(
          "debug.sethook(__janet_budget_hook, '', "
              + HOOK_GRANULARITY
              + "); "
              + "__janet_budget_hook=nil; io=nil; os=nil; package=nil; debug=nil; java=nil; "
              + "luajava=nil; load=nil; loadfile=nil; dofile=nil; collectgarbage=nil; require=nil; "
              + "__jthrowable__=nil");
    } catch (LuaException exception) {
      lua.close();
      throw new IllegalStateException("Could not initialize Lua sandbox", exception);
    }
  }

  private ScriptRuntimeException convert(Path fallback, Throwable failure) {
    String message =
        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    Matcher matcher = LINE.matcher(message);
    Path file = fallback;
    int line = 0;
    if (matcher.find()) {
      try {
        Path parsed = Path.of(matcher.group(1)).toAbsolutePath().normalize();
        if (parsed.startsWith(scriptPackage.root())) file = parsed;
      } catch (RuntimeException ignored) {
      }
      line = Integer.parseInt(matcher.group(2));
    }
    return new ScriptRuntimeException(file, line, message, failure);
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Lua runtime is closed");
  }

  private static ByteBuffer direct(byte[] source) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(source.length);
    buffer.put(source).flip();
    return buffer;
  }
}
