package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.ModuleInfo;

public final class ScriptModule extends Module {
  private final String scriptId;
  private final String localId;
  private final LuaScriptRuntime runtime;
  private final LuaRef table;
  private final List<String> dependencies;
  private final List<String> conflicts;
  private final BiConsumer<ScriptModule, RuntimeException> errorHandler;
  private final BiConsumer<ScriptModule, Boolean> stateListener;
  private final boolean requestedEnabled;
  private final OwnedResources resources = new OwnedResources();
  private final Map<String, ScriptSetting> scriptSettings = new LinkedHashMap<>();

  public ScriptModule(
      String scriptId,
      String localId,
      String name,
      String description,
      Category category,
      boolean enabled,
      LuaScriptRuntime runtime,
      LuaRef table,
      List<String> dependencies,
      List<String> conflicts,
      BiConsumer<ScriptModule, RuntimeException> errorHandler,
      BiConsumer<ScriptModule, Boolean> stateListener) {
    super(ModuleInfo.toggleable("script:" + scriptId + "/" + localId, name, description, category));
    this.scriptId = Objects.requireNonNull(scriptId, "scriptId");
    this.localId = Objects.requireNonNull(localId, "localId");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.table = Objects.requireNonNull(table, "table");
    this.dependencies = List.copyOf(dependencies);
    this.conflicts = List.copyOf(conflicts);
    this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    this.stateListener = Objects.requireNonNull(stateListener, "stateListener");
    requestedEnabled = enabled;
    setDefaultEnabled(false);
  }

  public void enableInitially() {
    if (requestedEnabled && !isEnabled()) setEnabled(true);
  }

  public ScriptSetting registerSetting(ScriptSetting setting) {
    if (scriptSettings.putIfAbsent(setting.getId(), setting) != null) {
      throw new IllegalArgumentException("Duplicate script setting id: " + setting.getId());
    }
    return add(setting);
  }

  public ScriptSetting findSetting(String id) {
    return scriptSettings.get(id);
  }

  public OwnedResources ownedResources() {
    return resources;
  }

  public String scriptId() {
    return scriptId;
  }

  public String localId() {
    return localId;
  }

  @Override
  protected void onInitialize() {
    for (String dependency : dependencies) {
      if (client().modules().find(dependency).isEmpty()) {
        throw new IllegalStateException("Missing module dependency: " + dependency);
      }
    }
    listen(FrameRenderEvent.class, event -> render(event.getTickDelta()));
  }

  @Override
  protected void onEnable() {
    for (String conflict : conflicts) {
      client()
          .modules()
          .find(conflict)
          .filter(Module::isEnabled)
          .filter(Module::isToggleable)
          .ifPresent(module -> module.setEnabled(false));
    }
    invoke("on_enable");
    stateListener.accept(this, true);
  }

  @Override
  protected void onDisable() {
    try {
      invoke("on_disable");
    } finally {
      stateListener.accept(this, false);
    }
  }

  @Override
  protected void onUpdate() {
    invoke("on_tick", object("phase", "start"));
  }

  public void render(float tickDelta) {
    invoke("on_render", object("tick_delta", tickDelta));
  }

  @Override
  public void writeConfig(dev.lifus.janetreborn.config.ConfigNode config) {
    invoke("on_config_save");
  }

  @Override
  public void readConfig(dev.lifus.janetreborn.config.ConfigNode config) {
    invoke("on_config_load");
  }

  @Override
  protected void onCleanup() {
    RuntimeException failure = null;
    try {
      invoke("on_cleanup");
    } catch (RuntimeException exception) {
      failure = exception;
    }
    try {
      resources.close();
    } catch (RuntimeException exception) {
      if (failure == null) failure = exception;
      else failure.addSuppressed(exception);
    }
    table.close();
    if (failure != null) throw failure;
  }

  public void discard() {
    if (isInitialized())
      throw new IllegalStateException("Initialized modules must be removed normally");
    resources.close();
    table.close();
  }

  private void invoke(String method, JsonElement... arguments) {
    try {
      runtime.invokeMethod(table, method, arguments);
    } catch (RuntimeException exception) {
      errorHandler.accept(this, exception);
      throw exception;
    }
  }

  private static JsonObject object(String key, String value) {
    JsonObject object = new JsonObject();
    object.addProperty(key, value);
    return object;
  }

  private static JsonObject object(String key, Number value) {
    JsonObject object = new JsonObject();
    object.addProperty(key, value);
    return object;
  }
}
