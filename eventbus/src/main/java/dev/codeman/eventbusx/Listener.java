package dev.codeman.eventbusx;

public interface Listener<T extends Event> {
  void call(T event);
}
