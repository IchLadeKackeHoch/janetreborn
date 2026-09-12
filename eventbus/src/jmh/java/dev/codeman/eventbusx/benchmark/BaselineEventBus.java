package dev.codeman.eventbusx.benchmark;

import dev.codeman.eventbusx.Event;
import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscription;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class BaselineEventBus {
  private final List<BaselineHandler> listeners = new ArrayList<>();
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile BaselineHandler[] cache = new BaselineHandler[0];

  void publish(Event event) {
    Objects.requireNonNull(event, "event");
    for (BaselineHandler listener : cache) {
      if (event.getClass() == listener.type) listener.listener.call(event);
    }
  }

  <E extends Event> Subscription subscribe(Class<E> type, Listener<E> listener) {
    return subscribe(type, Priority.DEFAULT, listener);
  }

  <E extends Event> Subscription subscribe(Class<E> type, int priority, Listener<E> listener) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(listener, "listener");
    BaselineSubscription subscription = new BaselineSubscription();
    BaselineHandler wrapper = BaselineHandler.create(type, priority, listener);
    subscription.wrapper = wrapper;
    lock.writeLock().lock();
    try {
      listeners.add(wrapper);
      listeners.sort(Comparator.comparingInt(BaselineHandler::priority).reversed());
      cache = listeners.toArray(BaselineHandler[]::new);
    } finally {
      lock.writeLock().unlock();
    }
    return subscription;
  }

  private final class BaselineSubscription implements Subscription {
    private BaselineHandler wrapper;
    private boolean active = true;

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public void close() {
      lock.writeLock().lock();
      try {
        if (!active) return;
        active = false;
        listeners.remove(wrapper);
        cache = listeners.toArray(BaselineHandler[]::new);
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  private record BaselineHandler(
      Class<? extends Event> type, int priority, Listener<Event> listener) {
    @SuppressWarnings("unchecked")
    private static <E extends Event> BaselineHandler create(
        Class<E> type, int priority, Listener<E> listener) {
      return new BaselineHandler(type, priority, (Listener<Event>) listener);
    }
  }
}
