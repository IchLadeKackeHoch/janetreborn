package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonObject;
import dev.lifus.janetreborn.api.ClientContext;
import dev.lifus.janetreborn.ui.Notices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ScriptManager implements AutoCloseable {
  public static final int DIAGNOSTIC_CAPACITY = 1_000;
  private final ClientContext client;
  private final Path scriptsDirectory;
  private final Path storageDirectory;
  private final ScriptDiscovery discovery;
  private final CapabilityStore capabilityStore;
  private final ScriptLimits limits;
  private final ScriptLogBuffer logs = new ScriptLogBuffer(2_000);
  private final ArrayDeque<ScriptDiagnostic> diagnostics = new ArrayDeque<>();
  private final Map<String, ScriptPackage> catalog = new LinkedHashMap<>();
  private final Map<String, ScriptInstance> instances = new LinkedHashMap<>();
  private final Map<String, ScriptState> states = new LinkedHashMap<>();
  private final Map<String, ScriptDiagnostic> latestErrors = new LinkedHashMap<>();
  private final Map<String, String> lastReloadResults = new LinkedHashMap<>();
  private final Map<String, Long> sourceStamps = new LinkedHashMap<>();
  private final Map<String, Long> notificationTimes = new LinkedHashMap<>();
  private final ScriptScheduler scheduler;
  private final ScriptEventBridge eventBridge;
  private boolean autoReload = true;
  private long ticks;
  private boolean closed;
  private Object lastLevel;
  private Object lastScreen;
  private int lastInventoryHash;

  public ScriptManager(ClientContext client, Path janetDirectory) {
    this(client, janetDirectory, ScriptLimits.DEFAULT);
  }

  public ScriptManager(ClientContext client, Path janetDirectory, ScriptLimits limits) {
    this.client = client;
    Path root = janetDirectory.toAbsolutePath().normalize();
    scriptsDirectory = root.resolve("scripts");
    storageDirectory = root.resolve("script-storage");
    this.limits = limits;
    discovery = new ScriptDiscovery(scriptsDirectory);
    capabilityStore = new CapabilityStore(root.resolve("script-capabilities.json"));
    scheduler = new ScriptScheduler(limits.scheduledTasks(), this::scheduledFailure);
    eventBridge = new ScriptEventBridge(client.events());
  }

  public synchronized void init() {
    requireOpen();
    refreshDiscovery();
    for (String id : List.copyOf(catalog.keySet())) load(id, new LinkedHashSet<>());
  }

  public synchronized void refreshDiscovery() {
    ScriptDiscovery.DiscoveryResult result = discovery.discover();
    catalog.clear();
    for (ScriptPackage scriptPackage : result.packages()) {
      catalog.put(scriptPackage.manifest().id(), scriptPackage);
      states.putIfAbsent(scriptPackage.manifest().id(), ScriptState.DISCOVERED);
    }
    for (ScriptDiagnostic diagnostic : result.diagnostics()) addDiagnostic(diagnostic);
    states.keySet().removeIf(id -> !catalog.containsKey(id) && !instances.containsKey(id));
  }

  public synchronized boolean load(String id) {
    return load(id, new LinkedHashSet<>());
  }

  private boolean load(String id, Set<String> stack) {
    requireOpen();
    ScriptInstance existing = instances.get(id);
    if (existing != null && existing.state() != ScriptState.UNLOADED) return true;
    ScriptPackage scriptPackage = catalog.get(id);
    if (scriptPackage == null) return false;
    if (!stack.add(id)) {
      recordManifestFailure(scriptPackage, "Cyclic script dependency: " + stack);
      return false;
    }
    if (!JanetSdkVersion.CURRENT.satisfies(scriptPackage.manifest().sdk())) {
      recordManifestFailure(
          scriptPackage,
          "Requires Janet SDK "
              + scriptPackage.manifest().sdk()
              + ", but this client provides "
              + JanetSdkVersion.CURRENT);
      return false;
    }
    Set<ScriptCapability> missing = capabilityStore.missing(scriptPackage.manifest());
    if (!missing.isEmpty()) {
      states.put(id, ScriptState.AWAITING_APPROVAL);
      log(id, ScriptSeverity.WARNING, "Waiting for capability approval: " + missing);
      return false;
    }
    for (String dependency : scriptPackage.manifest().dependencies()) {
      if (!load(dependency, stack)) {
        recordManifestFailure(scriptPackage, "Missing or unavailable dependency: " + dependency);
        return false;
      }
    }
    ScriptInstance candidate = new ScriptInstance(this, scriptPackage, client, limits);
    try {
      candidate.prepare();
      candidate.commit();
      instances.put(id, candidate);
      states.put(id, candidate.state());
      latestErrors.remove(id);
      sourceStamps.put(id, sourceStamp(scriptPackage.entryPoint()));
      log(id, ScriptSeverity.INFO, "Loaded " + scriptPackage.manifest().name());
      return true;
    } catch (RuntimeException exception) {
      states.put(id, ScriptState.ERROR);
      sourceStamps.put(id, sourceStamp(scriptPackage.entryPoint()));
      if (candidate.latestError() != null) latestErrors.put(id, candidate.latestError());
      return false;
    } finally {
      stack.remove(id);
    }
  }

  public synchronized boolean unload(String id) {
    ScriptInstance instance = instances.remove(id);
    if (instance == null) return false;
    instance.close();
    states.put(id, ScriptState.UNLOADED);
    log(id, ScriptSeverity.INFO, "Unloaded");
    return true;
  }

  public synchronized boolean reload(String id) {
    ScriptPackage scriptPackage = catalog.get(id);
    if (scriptPackage == null) return false;
    ScriptInstance current = instances.get(id);
    if (current == null) {
      boolean loaded = load(id);
      lastReloadResults.put(id, loaded ? "Loaded (no previous instance)" : "Load failed");
      return loaded;
    }
    if (!JanetSdkVersion.CURRENT.satisfies(scriptPackage.manifest().sdk())) {
      recordManifestFailure(
          scriptPackage, "Incompatible SDK requirement: " + scriptPackage.manifest().sdk());
      lastReloadResults.put(id, "Rejected: incompatible SDK requirement");
      return false;
    }
    Set<ScriptCapability> missing = capabilityStore.missing(scriptPackage.manifest());
    if (!missing.isEmpty()) {
      states.put(id, ScriptState.AWAITING_APPROVAL);
      log(id, ScriptSeverity.WARNING, "Reload needs capability approval: " + missing);
      lastReloadResults.put(id, "Awaiting capability approval");
      return false;
    }
    for (String dependency : scriptPackage.manifest().dependencies()) {
      if (!load(dependency)) {
        recordManifestFailure(scriptPackage, "Missing or unavailable dependency: " + dependency);
        lastReloadResults.put(id, "Failed: unavailable dependency " + dependency);
        return false;
      }
    }
    ScriptInstance candidate = new ScriptInstance(this, scriptPackage, client, limits);
    try {
      candidate.prepare();
    } catch (RuntimeException exception) {
      log(id, ScriptSeverity.ERROR, "Reload preparation failed; previous instance remains active");
      lastReloadResults.put(id, "Preparation failed; previous instance remains active");
      return false;
    }
    client.configs().saveModules();
    if (current != null) current.close();
    instances.remove(id);
    try {
      candidate.commit();
      instances.put(id, candidate);
      states.put(id, candidate.state());
      latestErrors.remove(id);
      sourceStamps.put(id, sourceStamp(scriptPackage.entryPoint()));
      client.configs().reloadModules();
      log(id, ScriptSeverity.INFO, "Reloaded successfully");
      lastReloadResults.put(id, "Succeeded at " + Instant.now());
      return true;
    } catch (RuntimeException exception) {
      states.put(id, ScriptState.ERROR);
      if (candidate.latestError() != null) latestErrors.put(id, candidate.latestError());
      log(id, ScriptSeverity.ERROR, "Reload failed during commit");
      lastReloadResults.put(id, "Failed during commit");
      return false;
    }
  }

  public synchronized int reloadAll() {
    refreshDiscovery();
    for (String id :
        instances.keySet().stream().filter(value -> !catalog.containsKey(value)).toList()) {
      unload(id);
      states.remove(id);
      sourceStamps.remove(id);
    }
    int successes = 0;
    for (String id : List.copyOf(catalog.keySet())) if (reload(id)) successes++;
    return successes;
  }

  public synchronized boolean enable(String id) {
    ScriptInstance instance = instances.get(id);
    if (instance == null) return load(id);
    instance.enable();
    states.put(id, instance.state());
    return instance.isActive();
  }

  public synchronized boolean disable(String id) {
    ScriptInstance instance = instances.get(id);
    if (instance == null) return false;
    instance.disable();
    states.put(id, instance.state());
    return true;
  }

  public synchronized void approve(String id, Set<ScriptCapability> capabilities)
      throws IOException {
    ScriptPackage scriptPackage = requirePackage(id);
    capabilityStore.approve(scriptPackage.manifest(), capabilities);
    states.put(id, ScriptState.DISCOVERED);
  }

  public synchronized Set<ScriptCapability> missingCapabilities(String id) {
    ScriptPackage scriptPackage = catalog.get(id);
    return scriptPackage == null ? Set.of() : capabilityStore.missing(scriptPackage.manifest());
  }

  public void tick() {
    publishClientStateChanges();
    scheduler.tick();
    boolean check;
    synchronized (this) {
      check = autoReload && ++ticks % 20 == 0;
    }
    if (check) pollChanges();
  }

  private void publishClientStateChanges() {
    if (client.minecraft() == null) return;
    Object level = client.minecraft().level;
    if (level != lastLevel) {
      if (lastLevel != null) eventBridge.fireSynthetic("world_leave", new JsonObject());
      lastLevel = level;
      if (level != null) {
        JsonObject event = new JsonObject();
        event.addProperty(
            "dimension", client.minecraft().level.dimension().identifier().toString());
        eventBridge.fireSynthetic("world_join", event);
      }
    }
    Object screen = client.minecraft().gui.screen();
    if (screen != lastScreen) {
      if (lastScreen != null) {
        JsonObject event = new JsonObject();
        event.addProperty("screen", lastScreen.getClass().getSimpleName());
        eventBridge.fireSynthetic("screen_close", event);
      }
      lastScreen = screen;
      if (screen != null) {
        JsonObject event = new JsonObject();
        event.addProperty("screen", screen.getClass().getSimpleName());
        eventBridge.fireSynthetic("screen_open", event);
      }
    }
    int inventoryHash = 1;
    if (client.minecraft().player != null) {
      var inventory = client.minecraft().player.getInventory();
      for (int slot = 0;
          slot < net.minecraft.world.entity.player.Inventory.getSelectionSize();
          slot++) {
        var stack = inventory.getItem(slot);
        inventoryHash = 31 * inventoryHash + stack.getCount();
        inventoryHash = 31 * inventoryHash + stack.getItem().hashCode();
        inventoryHash = 31 * inventoryHash + stack.getDamageValue();
      }
    }
    if (lastInventoryHash != 0 && inventoryHash != lastInventoryHash) {
      eventBridge.fireSynthetic("inventory_change", new JsonObject());
    }
    lastInventoryHash = inventoryHash;
  }

  public synchronized List<ScriptRecord> scripts() {
    List<ScriptRecord> records = new ArrayList<>();
    for (ScriptPackage scriptPackage : catalog.values()) {
      String id = scriptPackage.manifest().id();
      ScriptInstance instance = instances.get(id);
      long stamp = sourceStamp(scriptPackage.entryPoint());
      records.add(
          new ScriptRecord(
              scriptPackage,
              instance == null ? states.getOrDefault(id, ScriptState.DISCOVERED) : instance.state(),
              instance == null ? null : instance.lastLoad(),
              instance == null ? latestErrors.get(id) : instance.latestError(),
              lastReloadResults.get(id),
              sourceStamps.containsKey(id) && stamp != sourceStamps.get(id),
              missingCapabilities(id),
              instance == null ? List.of() : instance.modules()));
    }
    return List.copyOf(records);
  }

  public synchronized Optional<ScriptInstance> find(String id) {
    return Optional.ofNullable(instances.get(id));
  }

  public synchronized List<ScriptDiagnostic> diagnostics() {
    return List.copyOf(diagnostics);
  }

  public ScriptLogBuffer logs() {
    return logs;
  }

  public ScriptScheduler scheduler() {
    return scheduler;
  }

  public ScriptEventBridge eventBridge() {
    return eventBridge;
  }

  public Path scriptsDirectory() {
    return scriptsDirectory;
  }

  Path storageDirectory() {
    return storageDirectory;
  }

  public synchronized boolean isAutoReload() {
    return autoReload;
  }

  public synchronized void setAutoReload(boolean autoReload) {
    this.autoReload = autoReload;
  }

  public void log(String scriptId, ScriptSeverity severity, String message) {
    logs.add(scriptId, severity, message);
  }

  ScriptDiagnostic diagnostic(ScriptPackage scriptPackage, RuntimeException exception) {
    Path file = scriptPackage.entryPoint();
    int line = 0;
    if (exception instanceof ScriptRuntimeException runtimeException) {
      file = runtimeException.file();
      line = runtimeException.line();
    }
    ScriptDiagnostic diagnostic =
        ScriptDiagnostic.error(
            scriptPackage.manifest().id(), file, line, 0, exception.getMessage(), exception);
    addDiagnostic(diagnostic);
    log(scriptPackage.manifest().id(), ScriptSeverity.ERROR, exception.getMessage());
    notifyRateLimited(scriptPackage.manifest().id(), exception.getMessage());
    return diagnostic;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    List<ScriptInstance> loaded = new ArrayList<>(instances.values());
    loaded.sort(Comparator.comparing(instance -> instance.manifest().id()));
    for (ScriptInstance instance : loaded.reversed()) instance.close();
    instances.clear();
  }

  private synchronized void pollChanges() {
    ScriptDiscovery.DiscoveryResult result = discovery.discover();
    Map<String, ScriptPackage> found = new LinkedHashMap<>();
    for (ScriptPackage scriptPackage : result.packages()) {
      found.put(scriptPackage.manifest().id(), scriptPackage);
    }
    for (ScriptDiagnostic diagnostic : result.diagnostics()) addDiagnostic(diagnostic);

    for (String removed : catalog.keySet().stream().filter(id -> !found.containsKey(id)).toList()) {
      unload(removed);
      catalog.remove(removed);
      states.remove(removed);
      sourceStamps.remove(removed);
    }

    for (ScriptPackage scriptPackage : found.values()) {
      String id = scriptPackage.manifest().id();
      boolean added = !catalog.containsKey(id);
      catalog.put(id, scriptPackage);
      states.putIfAbsent(id, ScriptState.DISCOVERED);
      long stamp = sourceStamp(scriptPackage.entryPoint());
      Long previous = sourceStamps.get(id);
      if (added) load(id);
      else if (previous != null && stamp != previous) reload(id);
    }
  }

  private void scheduledFailure(String owner, RuntimeException exception) {
    synchronized (this) {
      ScriptInstance instance = instances.get(owner);
      if (instance != null) instance.recordFailure(exception);
    }
  }

  private synchronized void addDiagnostic(ScriptDiagnostic diagnostic) {
    diagnostics.addLast(diagnostic);
    while (diagnostics.size() > DIAGNOSTIC_CAPACITY) diagnostics.removeFirst();
  }

  private void recordManifestFailure(ScriptPackage scriptPackage, String message) {
    ScriptDiagnostic diagnostic =
        ScriptDiagnostic.error(
            scriptPackage.manifest().id(), scriptPackage.manifestPath(), 0, 0, message, null);
    addDiagnostic(diagnostic);
    latestErrors.put(scriptPackage.manifest().id(), diagnostic);
    states.put(scriptPackage.manifest().id(), ScriptState.ERROR);
    log(scriptPackage.manifest().id(), ScriptSeverity.ERROR, message);
  }

  private void notifyRateLimited(String scriptId, String message) {
    long now = System.nanoTime();
    synchronized (this) {
      long previous = notificationTimes.getOrDefault(scriptId, Long.MIN_VALUE);
      if (now - previous < 3_000_000_000L) return;
      notificationTimes.put(scriptId, now);
    }
    client
        .services()
        .find(Notices.class)
        .ifPresent(notices -> notices.hud("Lua: " + scriptId, message, Notices.Type.ERROR));
  }

  private ScriptPackage requirePackage(String id) {
    ScriptPackage scriptPackage = catalog.get(id);
    if (scriptPackage == null) throw new IllegalArgumentException("Unknown script: " + id);
    return scriptPackage;
  }

  private static long sourceStamp(Path source) {
    try {
      if (!Files.isRegularFile(source)) return 0;
      long metadata = Files.getLastModifiedTime(source).toMillis() ^ Files.size(source);
      return Long.rotateLeft(metadata, 17) ^ Arrays.hashCode(Files.readAllBytes(source));
    } catch (IOException exception) {
      return 0;
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Script manager is closed");
  }

  public record ScriptRecord(
      ScriptPackage scriptPackage,
      ScriptState state,
      Instant lastLoad,
      ScriptDiagnostic latestError,
      String lastReloadResult,
      boolean modified,
      Set<ScriptCapability> missingCapabilities,
      List<ScriptModule> modules) {}
}
