package dev.codeman.eventbusx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class EventBus {
  private static final Listener<Event>[] EMPTY_LISTENERS = emptyListeners();
  private static final Route[] EMPTY_ROUTES = new Route[0];
  private static final Route EMPTY_ROUTE = new Route(new Handler[0], EMPTY_LISTENERS);
  private static final ClassValue<SubscriberLayout> SUBSCRIBER_LAYOUTS =
      new ClassValue<>() {
        @Override
        protected SubscriberLayout computeValue(Class<?> type) {
          return SubscriberLayout.inspect(type);
        }
      };

  private final ReentrantLock mutationLock = new ReentrantLock();
  private final List<Handler> handlers = new ArrayList<>();
  private volatile Route[] routes = EMPTY_ROUTES;

  public void publish(Event event) {
    Objects.requireNonNull(event, "event");
    Route[] routes = this.routes;
    int typeId = event.eventBusTypeId();
    Route route = typeId < routes.length ? routes[typeId] : null;
    if (route == null) return;
    Listener<Event>[] listeners = route.listeners;
    if (listeners.length == 1) {
      listeners[0].call(event);
      return;
    }
    for (int index = 0, length = listeners.length; index < length; index++) {
      listeners[index].call(event);
    }
  }

  public <E extends Event> Subscription subscribe(Class<E> type, Listener<E> listener) {
    return subscribe(type, Priority.DEFAULT, listener);
  }

  public <E extends Event> Subscription subscribe(
      Class<E> type, int priority, Listener<E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    DirectSubscription subscription = new DirectSubscription();
    Handler handler = Handler.direct(subscription, type, priority, listener);
    subscription.handler = handler;

    mutationLock.lock();
    try {
      addLocked(handler);
    } finally {
      mutationLock.unlock();
    }
    return subscription;
  }

  public void subscribe(Object object) {
    Objects.requireNonNull(object, "object");
    mutationLock.lock();
    try {
      if (isSubscribedLocked(object)) return;
      addAllLocked(SUBSCRIBER_LAYOUTS.get(object.getClass()).bind(object));
    } finally {
      mutationLock.unlock();
    }
  }

  public void unsubscribe(Object object) {
    mutationLock.lock();
    try {
      List<Class<? extends Event>> affectedTypes = new ArrayList<>();
      for (int index = 0, size = handlers.size(); index < size; index++) {
        Handler handler = handlers.get(index);
        if (Objects.equals(handler.parent(), object) && !affectedTypes.contains(handler.type())) {
          affectedTypes.add(handler.type());
        }
      }
      if (!affectedTypes.isEmpty()) {
        handlers.removeIf(handler -> Objects.equals(handler.parent(), object));
        Route[] current = routes;
        Route[] updated = Arrays.copyOf(current, current.length);
        for (Class<? extends Event> type : affectedTypes) {
          int typeId = Event.typeId(type);
          Route replacement = current[typeId].withoutParent(object);
          updated[typeId] = replacement == EMPTY_ROUTE ? null : replacement;
        }
        routes = updated;
      }
    } finally {
      mutationLock.unlock();
    }
  }

  public boolean isSubscribed(Object object) {
    mutationLock.lock();
    try {
      return isSubscribedLocked(object);
    } finally {
      mutationLock.unlock();
    }
  }

  private void addLocked(Handler handler) {
    handlers.add(handler);
    replaceRoute(handler.type(), route(handler.type()).with(handler));
  }

  private void addAllLocked(Handler[] additions) {
    if (additions.length == 0) return;
    int highestTypeId = 0;
    for (Handler handler : additions) {
      highestTypeId = Math.max(highestTypeId, Event.typeId(handler.type()));
    }
    Route[] current = routes;
    int requiredLength = highestTypeId + 1;
    int newLength = Math.max(4, current.length);
    while (newLength < requiredLength) newLength <<= 1;
    Route[] updated = Arrays.copyOf(current, newLength);
    for (Handler handler : additions) {
      handlers.add(handler);
      int typeId = Event.typeId(handler.type());
      Route route = updated[typeId] == null ? EMPTY_ROUTE : updated[typeId];
      updated[typeId] = route.with(handler);
    }
    routes = updated;
  }

  private void replaceRoute(Class<? extends Event> type, Route replacement) {
    int typeId = Event.typeId(type);
    Route[] current = routes;
    int requiredLength = typeId + 1;
    int newLength = Math.max(4, current.length);
    while (newLength < requiredLength) newLength <<= 1;
    Route[] expanded = Arrays.copyOf(current, newLength);
    expanded[typeId] = replacement == EMPTY_ROUTE ? null : replacement;
    routes = expanded;
  }

  private Route route(Class<? extends Event> type) {
    int typeId = Event.typeId(type);
    Route[] current = routes;
    return typeId < current.length && current[typeId] != null ? current[typeId] : EMPTY_ROUTE;
  }

  private boolean isSubscribedLocked(Object object) {
    for (int index = 0, size = handlers.size(); index < size; index++) {
      if (Objects.equals(handlers.get(index).parent(), object)) return true;
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Listener<Event>[] emptyListeners() {
    return (Listener<Event>[]) new Listener<?>[0];
  }

  private final class DirectSubscription implements Subscription {
    private Handler handler;
    private volatile boolean active = true;

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public void close() {
      if (!active) return;
      mutationLock.lock();
      try {
        if (!active) return;
        active = false;
        handlers.remove(handler);
        replaceRoute(handler.type(), route(handler.type()).without(handler));
      } finally {
        mutationLock.unlock();
      }
    }
  }

  private static final class Route {
    private final Handler[] handlers;
    private final Listener<Event>[] listeners;

    private Route(Handler[] handlers, Listener<Event>[] listeners) {
      this.handlers = handlers;
      this.listeners = listeners;
    }

    private Route with(Handler handler) {
      int insertionIndex = 0;
      while (insertionIndex < handlers.length
          && handlers[insertionIndex].priority() >= handler.priority()) {
        insertionIndex++;
      }
      Handler[] nextHandlers = new Handler[handlers.length + 1];
      Listener<Event>[] nextListeners = newListeners(nextHandlers.length);
      System.arraycopy(handlers, 0, nextHandlers, 0, insertionIndex);
      System.arraycopy(listeners, 0, nextListeners, 0, insertionIndex);
      nextHandlers[insertionIndex] = handler;
      nextListeners[insertionIndex] = handler.listener();
      System.arraycopy(
          handlers,
          insertionIndex,
          nextHandlers,
          insertionIndex + 1,
          handlers.length - insertionIndex);
      System.arraycopy(
          listeners,
          insertionIndex,
          nextListeners,
          insertionIndex + 1,
          listeners.length - insertionIndex);
      return new Route(nextHandlers, nextListeners);
    }

    private Route without(Handler handler) {
      for (int index = 0; index < handlers.length; index++) {
        if (handlers[index] == handler) return withoutIndex(index);
      }
      return this;
    }

    private Route withoutParent(Object parent) {
      int remaining = 0;
      for (Handler handler : handlers) {
        if (!Objects.equals(handler.parent(), parent)) remaining++;
      }
      if (remaining == handlers.length) return this;
      if (remaining == 0) return EMPTY_ROUTE;
      Handler[] nextHandlers = new Handler[remaining];
      Listener<Event>[] nextListeners = newListeners(remaining);
      int destination = 0;
      for (int source = 0; source < handlers.length; source++) {
        if (Objects.equals(handlers[source].parent(), parent)) continue;
        nextHandlers[destination] = handlers[source];
        nextListeners[destination] = listeners[source];
        destination++;
      }
      return new Route(nextHandlers, nextListeners);
    }

    private Route withoutIndex(int removedIndex) {
      if (handlers.length == 1) return EMPTY_ROUTE;
      Handler[] nextHandlers = new Handler[handlers.length - 1];
      Listener<Event>[] nextListeners = newListeners(nextHandlers.length);
      System.arraycopy(handlers, 0, nextHandlers, 0, removedIndex);
      System.arraycopy(listeners, 0, nextListeners, 0, removedIndex);
      System.arraycopy(
          handlers,
          removedIndex + 1,
          nextHandlers,
          removedIndex,
          nextHandlers.length - removedIndex);
      System.arraycopy(
          listeners,
          removedIndex + 1,
          nextListeners,
          removedIndex,
          nextListeners.length - removedIndex);
      return new Route(nextHandlers, nextListeners);
    }

    @SuppressWarnings("unchecked")
    private static Listener<Event>[] newListeners(int length) {
      return (Listener<Event>[]) new Listener<?>[length];
    }
  }
}
