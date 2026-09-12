package dev.codeman.eventbusx;

public interface Subscription extends AutoCloseable {
  boolean isActive();

  @Override
  void close();
}
