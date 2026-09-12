package dev.lifus.janetreborn.scripting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class OwnedResources implements AutoCloseable {
  private final Deque<AutoCloseable> resources = new ArrayDeque<>();
  private boolean closed;

  public synchronized <T extends AutoCloseable> T own(T resource) {
    Objects.requireNonNull(resource, "resource");
    if (closed) {
      closeQuietly(resource);
      throw new IllegalStateException("Resource owner is already closed");
    }
    resources.addLast(resource);
    return resource;
  }

  public synchronized int size() {
    return resources.size();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    while (!resources.isEmpty()) {
      try {
        resources.removeLast().close();
      } catch (Exception exception) {
        if (failure == null) failure = new RuntimeException("Script resource cleanup failed");
        failure.addSuppressed(exception);
      }
    }
    if (failure != null) throw failure;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  private static void closeQuietly(AutoCloseable resource) {
    try {
      resource.close();
    } catch (Exception ignored) {
    }
  }
}
