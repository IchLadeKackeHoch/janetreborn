package opsec.misuyaka.sdk;

import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.EventBus;
import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscription;
import dev.lifus.janetreborn.api.ClientContext;
import dev.lifus.janetreborn.command.Command;
import dev.lifus.janetreborn.command.CommandHandle;
import dev.lifus.janetreborn.config.ConfigNode;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;
import opsec.misuyaka.sdk.setting.KeySetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;
import opsec.misuyaka.sdk.setting.Setting;

public abstract class Module {
  private final ModuleInfo info;
  private final Settings settings = new Settings();
  private final KeySetting key;
  private final ModeSetting<BindMode> bindMode;
  private final List<EventBinding<?>> bindings = new ArrayList<>();
  private final List<CommandHandle> commandHandles = new ArrayList<>();
  private ClientContext client;
  private boolean enabled;
  private boolean initialized;
  private boolean cleaned;
  private boolean cleaning;
  private boolean keyDown;

  protected Module(ModuleInfo info) {
    this.info = Objects.requireNonNull(info, "info");
    enabled = !info.toggleable();
    key = settings.key("Keybind");
    bindMode = settings.mode("Keybind Mode", BindMode.TOGGLE);
  }

  protected Module(String name, String description, Category category) {
    this(ModuleInfo.toggleable(name, description, category));
  }

  protected Module(String name, String description, Category category, boolean toggleable) {
    this(new ModuleInfo(name, description, category, toggleable));
  }

  public final void initialize(ClientContext client) {
    if (initialized || cleaned)
      throw new IllegalStateException("Module cannot be initialized twice");
    this.client = Objects.requireNonNull(client, "client");
    initialized = true;
    try {
      onInitialize();
      if (enabled) {
        activateSubscriptions();
        onEnable();
      }
    } catch (RuntimeException exception) {
      deactivateSubscriptions();
      for (CommandHandle registration : commandHandles) registration.close();
      commandHandles.clear();
      initialized = false;
      this.client = null;
      throw new ModuleException(this, "initialization", exception);
    }
  }

  public final void setEnabled(boolean enabled) {
    if (!info.toggleable() || this.enabled == enabled) return;
    requireInitialized();
    if (enabled) enable();
    else disable();
  }

  public final void toggle() {
    setEnabled(!enabled);
  }

  public final void update() {
    if (!enabled || !initialized || cleaned) return;
    try {
      onUpdate();
    } catch (RuntimeException exception) {
      throw new ModuleException(this, "update", exception);
    }
  }

  public final void cleanup() {
    if (!initialized || cleaned || cleaning) return;
    cleaning = true;
    RuntimeException failure = null;
    if (enabled) {
      enabled = false;
      deactivateSubscriptions();
      try {
        onDisable();
      } catch (RuntimeException exception) {
        failure = new ModuleException(this, "disable", exception);
      }
    } else {
      deactivateSubscriptions();
    }
    for (CommandHandle registration : commandHandles) registration.close();
    commandHandles.clear();
    try {
      onCleanup();
    } catch (RuntimeException exception) {
      if (failure == null) failure = new ModuleException(this, "cleanup", exception);
      else failure.addSuppressed(exception);
    }
    cleaned = true;
    cleaning = false;
    if (failure != null) throw failure;
  }

  protected void onInitialize() {}

  protected void onEnable() {}

  protected void onDisable() {}

  protected void onUpdate() {}

  protected void onCleanup() {}

  public void writeConfig(ConfigNode config) {}

  public void readConfig(ConfigNode config) {}

  protected final <E extends Event> void listen(Class<E> type, Listener<E> listener) {
    listen(type, Priority.DEFAULT, listener);
  }

  protected final <E extends Event> void listen(Class<E> type, int priority, Listener<E> listener) {
    requireInitialized();
    EventBinding<E> binding = new EventBinding<>(type, priority, listener);
    bindings.add(binding);
    if (enabled) binding.activate(client.events());
  }

  protected final CommandHandle command(String name, String description, Command handler) {
    requireInitialized();
    CommandHandle registration = client.commands().register(name, description, handler);
    commandHandles.add(registration);
    return registration;
  }

  protected final ClientContext client() {
    requireInitialized();
    return client;
  }

  protected final <S> S service(Class<S> type) {
    return client().services().require(type);
  }

  protected final BoolSetting bool(String name, boolean defaultValue) {
    return settings.bool(name, defaultValue);
  }

  protected final <N extends Number & Comparable<N>> NumberSetting<N> number(
      String name, N defaultValue, N min, N max, N step) {
    return settings.number(name, defaultValue, min, max, step);
  }

  protected final <N extends Number & Comparable<N>> RangeSetting<N> range(
      String name, Range<N> defaultValue, N min, N max, N step) {
    return settings.range(name, defaultValue, min, max, step);
  }

  protected final <E extends Enum<E>> ModeSetting<E> mode(String name, E defaultValue) {
    return settings.mode(name, defaultValue);
  }

  protected final ColorSetting color(String name, Color defaultValue) {
    return settings.color(name, defaultValue);
  }

  protected final <S extends Setting<?>> S add(S setting) {
    return settings.add(setting);
  }

  public final ModuleInfo info() {
    return info;
  }

  public final Settings settings() {
    return settings;
  }

  public final String getName() {
    return info.name();
  }

  protected final void setDefaultEnabled(boolean enabled) {
    if (initialized || cleaned) throw new IllegalStateException("Module is already initialized");
    if (!info.toggleable() && !enabled) {
      throw new IllegalArgumentException("Internal modules must remain enabled");
    }
    this.enabled = enabled;
  }

  public final String getId() {
    return info.id();
  }

  public final String getDescription() {
    return info.description();
  }

  public final Category getCategory() {
    return info.category();
  }

  public final List<Setting<?>> getSettings() {
    return settings.all();
  }

  public final boolean isEnabled() {
    return enabled;
  }

  public final boolean isToggleable() {
    return info.toggleable();
  }

  public final boolean isInitialized() {
    return initialized && !cleaned;
  }

  public final KeySetting getKeybind() {
    return key;
  }

  public final ModeSetting<BindMode> getKeybindMode() {
    return bindMode;
  }

  public final void updateKeybind(boolean down) {
    if (bindMode.getValue() == BindMode.HOLD) setEnabled(down);
    else if (down && !keyDown) toggle();
    keyDown = down;
  }

  public final void releaseKeybind() {
    if (bindMode.getValue() == BindMode.HOLD && key.isBound()) setEnabled(false);
    keyDown = false;
  }

  private void enable() {
    this.enabled = true;
    try {
      activateSubscriptions();
      onEnable();
    } catch (RuntimeException exception) {
      this.enabled = false;
      deactivateSubscriptions();
      throw new ModuleException(this, "enable", exception);
    }
  }

  private void disable() {
    this.enabled = false;
    deactivateSubscriptions();
    try {
      onDisable();
    } catch (RuntimeException exception) {
      throw new ModuleException(this, "disable", exception);
    }
  }

  private void activateSubscriptions() {
    client.events().subscribe(this);
    for (EventBinding<?> binding : bindings) binding.activate(client.events());
  }

  private void deactivateSubscriptions() {
    if (client == null) return;
    client.events().unsubscribe(this);
    for (EventBinding<?> binding : bindings) binding.deactivate();
  }

  private void requireInitialized() {
    if (!initialized || cleaned)
      throw new IllegalStateException("Module is not active: " + getName());
  }

  private static final class EventBinding<E extends Event> {
    private final Class<E> type;
    private final int priority;
    private final Listener<E> listener;
    private Subscription subscription;

    private EventBinding(Class<E> type, int priority, Listener<E> listener) {
      this.type = Objects.requireNonNull(type, "type");
      this.priority = priority;
      this.listener = Objects.requireNonNull(listener, "listener");
    }

    private void activate(EventBus events) {
      if (subscription == null || !subscription.isActive()) {
        subscription = events.subscribe(type, priority, listener);
      }
    }

    private void deactivate() {
      if (subscription != null) subscription.close();
      subscription = null;
    }
  }
}
