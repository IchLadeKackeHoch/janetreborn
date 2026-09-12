package opsec.misuyaka.sdk;

import dev.codeman.eventbusx.EventBus;
import dev.lifus.janetreborn.api.ClientContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.glfw.GLFW;

@Slf4j
public final class Modules {
  private final EventBus eventBus;
  private final Map<Class<? extends Module>, Module> byType = new LinkedHashMap<>();
  private final Map<String, Module> byId = new LinkedHashMap<>();
  private final Map<String, Module> byName = new LinkedHashMap<>();
  private ClientContext client;

  public Modules(EventBus eventBus) {
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
  }

  public void bind(ClientContext client) {
    if (this.client != null && this.client != client) {
      throw new IllegalStateException("Module manager is already bound");
    }
    if (client.events() != eventBus) {
      throw new IllegalArgumentException("Client context uses a different event bus");
    }
    this.client = Objects.requireNonNull(client, "client");
  }

  public <M extends Module> M register(ModuleFactory<M> factory) {
    requireBound();
    return add(Objects.requireNonNull(factory, "factory").create(client));
  }

  public <M extends Module> M add(M module) {
    requireBound();
    Objects.requireNonNull(module, "module");
    @SuppressWarnings("unchecked")
    Class<? extends Module> type = (Class<? extends Module>) module.getClass();
    String id = normalize(module.getId());
    String name = normalize(module.getName());
    if (byId.containsKey(id))
      throw new IllegalArgumentException("Module id already registered: " + id);
    module.initialize(client);
    byType.putIfAbsent(type, module);
    byId.put(id, module);
    byName.putIfAbsent(name, module);
    return module;
  }

  public <M extends Module> M get(Class<M> type) {
    return type.cast(byType.get(Objects.requireNonNull(type, "type")));
  }

  public <M extends Module> Optional<M> find(Class<M> type) {
    return Optional.ofNullable(get(type));
  }

  public Optional<Module> find(String name) {
    String normalized = normalize(name);
    Module module = byId.get(normalized);
    return Optional.ofNullable(module == null ? byName.get(normalized) : module);
  }

  public Optional<Module> findById(String id) {
    return Optional.ofNullable(byId.get(normalize(id)));
  }

  public <M extends Module> M require(Class<M> type) {
    return find(type)
        .orElseThrow(
            () -> new IllegalStateException("Module is not registered: " + type.getName()));
  }

  public <M extends Module> Optional<M> remove(Class<M> type) {
    M module = get(type);
    if (module == null) return Optional.empty();
    remove(module);
    return Optional.of(module);
  }

  public Optional<Module> remove(String id) {
    Module module = byId.get(normalize(id));
    if (module == null) return Optional.empty();
    remove(module);
    return Optional.of(module);
  }

  public boolean remove(Module module) {
    Objects.requireNonNull(module, "module");
    if (!byId.remove(normalize(module.getId()), module)) return false;
    byName.remove(normalize(module.getName()), module);
    Class<? extends Module> type = module.getClass();
    if (byType.remove(type, module)) {
      byId.values().stream()
          .filter(type::isInstance)
          .findFirst()
          .ifPresent(next -> byType.put(type, next));
    }
    module.cleanup();
    return true;
  }

  public Collection<Module> getAll() {
    return List.copyOf(byId.values());
  }

  public void update() {
    for (Module module : getAll()) {
      try {
        module.update();
      } catch (ModuleException exception) {
        log.error("Disabling module after update failure: {}", module.getName(), exception);
        if (module.isToggleable() && module.isEnabled()) {
          try {
            module.setEnabled(false);
          } catch (ModuleException disableFailure) {
            exception.addSuppressed(disableFailure);
          }
        }
      }
    }
  }

  public void cleanup() {
    List<Module> modules = getAll().stream().toList().reversed();
    for (Module module : modules) {
      try {
        module.cleanup();
      } catch (ModuleException exception) {
        log.error("Could not clean up module {}", module.getName(), exception);
      }
    }
    byType.clear();
    byId.clear();
    byName.clear();
  }

  public void updateKeybinds(long window, boolean inputBlocked) {
    for (Module module : getAll()) {
      if (!module.isToggleable()) continue;
      if (inputBlocked || !module.getKeybind().isBound()) {
        module.releaseKeybind();
        continue;
      }
      module.updateKeybind(
          GLFW.glfwGetKey(window, module.getKeybind().getValue()) == GLFW.GLFW_PRESS);
    }
  }

  private void requireBound() {
    if (client == null) throw new IllegalStateException("Module manager is not bound");
  }

  private static String normalize(String name) {
    return Objects.requireNonNull(name, "name").trim().toLowerCase(Locale.ROOT);
  }
}
