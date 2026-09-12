package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonElement;
import dev.lifus.janetreborn.api.ClientContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class ScriptInstance implements AutoCloseable {
  private final ScriptManager manager;
  private final ScriptPackage scriptPackage;
  private final ClientContext client;
  private final ScriptLimits limits;
  private final OwnedResources resources = new OwnedResources();
  private final List<ScriptModule> modules = new ArrayList<>();
  private final List<PendingRegistration> pending = new ArrayList<>();
  private final Set<String> enabledBeforeDisable = new LinkedHashSet<>();
  private LuaScriptRuntime runtime;
  private ScriptStorage storage;
  private ScriptState state = ScriptState.DISCOVERED;
  private ScriptDiagnostic latestError;
  private Instant lastLoad;
  private int overruns;
  private boolean active;

  ScriptInstance(
      ScriptManager manager,
      ScriptPackage scriptPackage,
      ClientContext client,
      ScriptLimits limits) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.scriptPackage = Objects.requireNonNull(scriptPackage, "scriptPackage");
    this.client = Objects.requireNonNull(client, "client");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  void prepare() {
    requireState(ScriptState.DISCOVERED);
    state = ScriptState.LOADING;
    try {
      runtime = resources.own(new LuaScriptRuntime(scriptPackage, limits));
      storage =
          resources.own(
              new ScriptStorage(manager.storageDirectory(), scriptPackage.manifest().id()));
      new LuaSdkBindings(this, client).install();
      runtime.executeEntryPoint();
      migrateSchema();
    } catch (RuntimeException exception) {
      fail(exception);
      discardCandidates();
      closeResources();
      throw exception;
    }
  }

  void commit() {
    requireState(ScriptState.LOADING);
    List<ScriptModule> added = new ArrayList<>();
    try {
      for (ScriptModule module : modules) {
        client.modules().add(module);
        added.add(module);
        resources.own(() -> client.modules().remove(module));
      }
      active = true;
      state = ScriptState.ENABLED;
      for (PendingRegistration registration : pending) registration.install();
      for (ScriptModule module : modules) module.enableInitially();
      lastLoad = Instant.now();
      latestError = null;
    } catch (RuntimeException exception) {
      active = false;
      fail(exception);
      for (ScriptModule module : modules) {
        if (!added.contains(module)) {
          try {
            module.discard();
          } catch (RuntimeException ignored) {
          }
        }
      }
      closeResources();
      throw exception;
    }
  }

  public DeferredHandle stage(Supplier<? extends AutoCloseable> installer) {
    return stage(installer, resources);
  }

  public DeferredHandle stage(
      Supplier<? extends AutoCloseable> installer, OwnedResources resourceOwner) {
    if (state != ScriptState.LOADING)
      throw new IllegalStateException("Registrations are load-only");
    DeferredHandle handle = new DeferredHandle();
    pending.add(new PendingRegistration(installer, resourceOwner, handle));
    return handle;
  }

  public void addModule(ScriptModule module) {
    if (state != ScriptState.LOADING) throw new IllegalStateException("Modules are load-only");
    if (modules.stream().anyMatch(existing -> existing.getId().equals(module.getId()))) {
      module.discard();
      throw new IllegalArgumentException("Duplicate script module id: " + module.localId());
    }
    modules.add(module);
  }

  public void own(AutoCloseable resource) {
    resources.own(resource);
  }

  public void disable() {
    if (state != ScriptState.ENABLED) return;
    active = false;
    enabledBeforeDisable.clear();
    for (ScriptModule module : modules) {
      if (module.isEnabled()) {
        enabledBeforeDisable.add(module.getId());
        try {
          module.setEnabled(false);
        } catch (RuntimeException exception) {
          recordFailure(exception);
        }
      }
    }
    state = ScriptState.DISABLED;
  }

  public void enable() {
    if (state != ScriptState.DISABLED && state != ScriptState.PAUSED) return;
    overruns = 0;
    active = true;
    state = ScriptState.ENABLED;
    for (ScriptModule module : modules) {
      if (enabledBeforeDisable.contains(module.getId())) module.setEnabled(true);
    }
  }

  public void recordFailure(RuntimeException exception) {
    ScriptDiagnostic diagnostic = manager.diagnostic(scriptPackage, exception);
    latestError = diagnostic;
    if (exception instanceof ScriptBudgetException
        || exception.getCause() instanceof ScriptBudgetException) {
      overruns++;
      if (overruns >= limits.repeatedOverruns()) pause();
    }
  }

  public void pause() {
    if (!active) return;
    disable();
    state = ScriptState.PAUSED;
  }

  @Override
  public void close() {
    if (state == ScriptState.UNLOADED) return;
    active = false;
    closeResources();
    state = ScriptState.UNLOADED;
  }

  public ScriptPackage scriptPackage() {
    return scriptPackage;
  }

  public ScriptManifest manifest() {
    return scriptPackage.manifest();
  }

  public LuaScriptRuntime runtime() {
    return runtime;
  }

  public ScriptStorage storage() {
    return storage;
  }

  public List<ScriptModule> modules() {
    return List.copyOf(modules);
  }

  public ScriptState state() {
    return state;
  }

  public ScriptDiagnostic latestError() {
    return latestError;
  }

  public Instant lastLoad() {
    return lastLoad;
  }

  public boolean isActive() {
    return active && state == ScriptState.ENABLED;
  }

  public ScriptManager manager() {
    return manager;
  }

  private void pauseAfterFailure() {
    active = false;
    state = ScriptState.PAUSED;
  }

  private void fail(RuntimeException exception) {
    active = false;
    state = ScriptState.ERROR;
    latestError = manager.diagnostic(scriptPackage, exception);
  }

  private void discardCandidates() {
    for (ScriptModule module : modules) {
      try {
        module.discard();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void closeResources() {
    try {
      resources.close();
    } catch (RuntimeException exception) {
      manager.log(manifest().id(), ScriptSeverity.ERROR, exception.getMessage());
    }
  }

  private void migrateSchema() {
    int current = manifest().schemaVersion();
    int stored =
        storage
            .get("__janet_schema_version")
            .filter(JsonElement::isJsonPrimitive)
            .map(JsonElement::getAsInt)
            .orElse(0);
    if (stored > current) {
      throw new IllegalStateException(
          "Stored schema version " + stored + " is newer than package schema " + current);
    }
    if (stored == current) return;
    LuaRef migration = runtime.globalFunction("janet_migrate");
    if (migration != null) {
      try {
        runtime.invoke(
            migration,
            new com.google.gson.JsonPrimitive(stored),
            new com.google.gson.JsonPrimitive(current));
      } finally {
        migration.close();
      }
    }
    storage.put("__janet_schema_version", new com.google.gson.JsonPrimitive(current));
  }

  private void requireState(ScriptState expected) {
    if (state != expected)
      throw new IllegalStateException("Expected " + expected + ", was " + state);
  }

  private record PendingRegistration(
      Supplier<? extends AutoCloseable> installer, OwnedResources owner, DeferredHandle handle) {
    private void install() {
      if (handle.closed) return;
      handle.delegate = owner.own(installer.get());
    }
  }

  public static final class DeferredHandle implements AutoCloseable {
    private AutoCloseable delegate;
    private boolean closed;

    public boolean isActive() {
      return !closed;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      if (delegate == null) return;
      try {
        delegate.close();
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }
}
