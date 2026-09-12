package dev.codeman.eventbusx;

record Handler(Object parent, Class<? extends Event> type, int priority, Listener<Event> listener) {
  @SuppressWarnings("unchecked")
  static <E extends Event> Handler direct(
      Object parent, Class<E> type, int priority, Listener<E> listener) {
    return new Handler(parent, type, priority, (Listener<Event>) listener);
  }
}
