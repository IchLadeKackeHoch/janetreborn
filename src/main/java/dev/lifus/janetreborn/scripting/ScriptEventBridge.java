package dev.lifus.janetreborn.scripting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.EventBus;
import dev.codeman.eventbusx.Subscription;
import dev.lifus.janetreborn.event.game.ChatSendEvent;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import dev.lifus.janetreborn.event.game.KeyInputEvent;
import dev.lifus.janetreborn.event.game.MouseInputEvent;
import dev.lifus.janetreborn.event.game.MovementInputEvent;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.event.game.TotemActivatedEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ScriptEventBridge {
  private static final Set<String> SYNTHETIC =
      Set.of(
          "world_join",
          "world_leave",
          "screen_open",
          "screen_close",
          "inventory_change",
          "module_change");
  private final EventBus events;
  private final List<SyntheticListener> synthetic = new ArrayList<>();
  private long sequence;

  public ScriptEventBridge(EventBus events) {
    this.events = Objects.requireNonNull(events, "events");
  }

  public AutoCloseable subscribe(
      String eventName,
      int priority,
      boolean once,
      LuaScriptRuntime runtime,
      LuaRef callback,
      LuaRef filter,
      Predicate<Void> active,
      Consumer<RuntimeException> failureHandler) {
    String normalized = Objects.requireNonNull(eventName, "eventName").trim().toLowerCase();
    if (SYNTHETIC.contains(normalized)) {
      SyntheticListener listener =
          new SyntheticListener(
              normalized,
              priority,
              sequence++,
              once,
              runtime,
              callback,
              filter,
              active,
              failureHandler);
      synchronized (synthetic) {
        if (synthetic.size() >= 1_024)
          throw new IllegalStateException("Synthetic event listener limit exceeded");
        synthetic.add(listener);
      }
      return listener;
    }
    Subscription[] holder = new Subscription[1];
    Consumer<Event> invoke =
        event -> {
          if (!active.test(null)) return;
          JsonObject snapshot = snapshot(normalized, event);
          try {
            if (filter != null && !runtime.invoke(filter, snapshot).getAsBoolean()) return;
            JsonElement result = runtime.invoke(callback, snapshot);
            if (isCancellable(event) && result.isJsonObject()) {
              JsonElement cancel = result.getAsJsonObject().get("cancel");
              if (cancel != null && cancel.getAsBoolean()) event.setCancelled(true);
            }
            if (once && holder[0] != null) holder[0].close();
          } catch (RuntimeException exception) {
            failureHandler.accept(exception);
            if (once && holder[0] != null) holder[0].close();
          }
        };
    holder[0] = subscribeNative(normalized, priority, invoke);
    return holder[0];
  }

  public void fireSynthetic(String eventName, JsonObject snapshot) {
    String normalized = eventName.trim().toLowerCase();
    List<SyntheticListener> listeners;
    synchronized (synthetic) {
      listeners =
          synthetic.stream()
              .filter(listener -> listener.name.equals(normalized) && listener.activeHandle)
              .sorted(
                  Comparator.comparingInt((SyntheticListener listener) -> listener.priority)
                      .reversed()
                      .thenComparingLong(listener -> listener.sequence))
              .toList();
    }
    JsonObject event = snapshot.deepCopy();
    event.addProperty("name", normalized);
    event.addProperty("thread", "client");
    event.addProperty("cancellable", false);
    event.addProperty("cancelled", false);
    for (SyntheticListener listener : listeners) listener.invoke(event);
  }

  @SuppressWarnings("unchecked")
  private Subscription subscribeNative(String name, int priority, Consumer<Event> callback) {
    return switch (name) {
      case "client_tick", "tick" -> events.subscribe(TickEvent.class, priority, callback::accept);
      case "client_tick_end", "tick_end" ->
          events.subscribe(TickEndEvent.class, priority, callback::accept);
      case "hud_render", "world_render", "render" ->
          events.subscribe(FrameRenderEvent.class, priority, callback::accept);
      case "server_teleport" ->
          events.subscribe(ServerTeleportEvent.class, priority, callback::accept);
      case "movement_input" ->
          events.subscribe(MovementInputEvent.class, priority, callback::accept);
      case "post_input" -> events.subscribe(PostInputEvent.class, priority, callback::accept);
      case "totem_activated" ->
          events.subscribe(TotemActivatedEvent.class, priority, callback::accept);
      case "key_input" -> events.subscribe(KeyInputEvent.class, priority, callback::accept);
      case "mouse_input" -> events.subscribe(MouseInputEvent.class, priority, callback::accept);
      case "chat_send" -> events.subscribe(ChatSendEvent.class, priority, callback::accept);
      default -> throw new IllegalArgumentException("Unsupported event: " + name);
    };
  }

  private static JsonObject snapshot(String name, Event event) {
    JsonObject data = new JsonObject();
    data.addProperty("name", name);
    data.addProperty("thread", "client");
    data.addProperty("cancellable", isCancellable(event));
    data.addProperty("cancelled", event.isCancelled());
    if (event instanceof FrameRenderEvent render) {
      data.addProperty("thread", "render");
      data.addProperty("tick_delta", render.getTickDelta());
    } else if (event instanceof KeyInputEvent input) {
      data.addProperty("key", input.key());
      data.addProperty("action", input.action());
    } else if (event instanceof MouseInputEvent input) {
      data.addProperty("kind", input.kind());
      data.addProperty("button", input.button());
      data.addProperty("action", input.action());
      data.addProperty("horizontal", input.horizontal());
      data.addProperty("vertical", input.vertical());
    } else if (event instanceof ChatSendEvent chat) {
      data.addProperty("message", chat.message());
    } else if (event instanceof MovementInputEvent input) {
      data.addProperty("claimed", input.isClaimed());
    }
    return data;
  }

  private static boolean isCancellable(Event event) {
    return event instanceof KeyInputEvent
        || event instanceof MouseInputEvent
        || event instanceof ChatSendEvent;
  }

  private final class SyntheticListener implements AutoCloseable {
    private final String name;
    private final int priority;
    private final long sequence;
    private final boolean once;
    private final LuaScriptRuntime runtime;
    private final LuaRef callback;
    private final LuaRef filter;
    private final Predicate<Void> active;
    private final Consumer<RuntimeException> failureHandler;
    private boolean activeHandle = true;

    private SyntheticListener(
        String name,
        int priority,
        long sequence,
        boolean once,
        LuaScriptRuntime runtime,
        LuaRef callback,
        LuaRef filter,
        Predicate<Void> active,
        Consumer<RuntimeException> failureHandler) {
      this.name = name;
      this.priority = priority;
      this.sequence = sequence;
      this.once = once;
      this.runtime = runtime;
      this.callback = callback;
      this.filter = filter;
      this.active = active;
      this.failureHandler = failureHandler;
    }

    private void invoke(JsonObject event) {
      if (!activeHandle || !active.test(null)) return;
      try {
        if (filter != null && !runtime.invoke(filter, event).getAsBoolean()) return;
        runtime.invoke(callback, event);
      } catch (RuntimeException exception) {
        failureHandler.accept(exception);
      } finally {
        if (once) close();
      }
    }

    @Override
    public void close() {
      synchronized (synthetic) {
        if (!activeHandle) return;
        activeHandle = false;
        synthetic.remove(this);
      }
    }
  }
}
