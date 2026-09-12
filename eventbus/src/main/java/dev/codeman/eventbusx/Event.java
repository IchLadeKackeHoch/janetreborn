package dev.codeman.eventbusx;

import java.util.concurrent.atomic.AtomicInteger;

public class Event {
  private static final AtomicInteger NEXT_TYPE_ID = new AtomicInteger();
  private static final ClassValue<Integer> TYPE_IDS =
      new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
          return NEXT_TYPE_ID.getAndIncrement();
        }
      };

  private final int eventBusTypeId = typeId(getClass());
  private boolean cancelled;

  static int typeId(Class<? extends Event> type) {
    return TYPE_IDS.get(type);
  }

  final int eventBusTypeId() {
    return eventBusTypeId;
  }

  public void setCancelled(boolean cancelled) {
    this.cancelled = cancelled;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
