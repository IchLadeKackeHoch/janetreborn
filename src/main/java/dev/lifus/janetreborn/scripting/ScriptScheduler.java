package dev.lifus.janetreborn.scripting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class ScriptScheduler {
  private final int maximumPerOwner;
  private final BiConsumer<String, RuntimeException> errorHandler;
  private final List<Task> tasks = new ArrayList<>();
  private long tick;
  private long sequence;

  public ScriptScheduler(int maximumPerOwner, BiConsumer<String, RuntimeException> errorHandler) {
    if (maximumPerOwner < 1) throw new IllegalArgumentException("Task limit must be positive");
    this.maximumPerOwner = maximumPerOwner;
    this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
  }

  public synchronized ScheduledHandle schedule(
      String owner, long delayTicks, long intervalTicks, Runnable callback) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(callback, "callback");
    if (delayTicks < 0 || intervalTicks < 0) throw new IllegalArgumentException("Negative delay");
    long owned = tasks.stream().filter(task -> task.active && task.owner.equals(owner)).count();
    if (owned >= maximumPerOwner) throw new IllegalStateException("Scheduled-task limit exceeded");
    Task task =
        new Task(owner, tick + Math.max(1, delayTicks), intervalTicks, sequence++, callback);
    tasks.add(task);
    return task;
  }

  public void tick() {
    List<Task> ready;
    synchronized (this) {
      tick++;
      ready =
          tasks.stream()
              .filter(task -> task.active && task.dueTick <= tick)
              .sorted(
                  Comparator.comparingLong((Task task) -> task.dueTick)
                      .thenComparingLong(task -> task.sequence))
              .toList();
    }
    for (Task task : ready) {
      synchronized (this) {
        if (!task.active) continue;
        if (task.interval == 0) task.active = false;
        else task.dueTick = tick + task.interval;
      }
      try {
        task.callback.run();
      } catch (RuntimeException exception) {
        errorHandler.accept(task.owner, exception);
      }
    }
    synchronized (this) {
      tasks.removeIf(task -> !task.active);
    }
  }

  public synchronized void cancelOwned(String owner) {
    for (Task task : tasks) if (task.owner.equals(owner)) task.active = false;
    tasks.removeIf(task -> !task.active);
  }

  public synchronized int size(String owner) {
    return (int) tasks.stream().filter(task -> task.active && task.owner.equals(owner)).count();
  }

  public interface ScheduledHandle extends AutoCloseable {
    boolean isActive();

    @Override
    void close();
  }

  private final class Task implements ScheduledHandle {
    private final String owner;
    private long dueTick;
    private final long interval;
    private final long sequence;
    private final Runnable callback;
    private boolean active = true;

    private Task(String owner, long dueTick, long interval, long sequence, Runnable callback) {
      this.owner = owner;
      this.dueTick = dueTick;
      this.interval = interval;
      this.sequence = sequence;
      this.callback = callback;
    }

    @Override
    public boolean isActive() {
      synchronized (ScriptScheduler.this) {
        return active;
      }
    }

    @Override
    public void close() {
      synchronized (ScriptScheduler.this) {
        active = false;
        for (Iterator<Task> iterator = tasks.iterator(); iterator.hasNext(); ) {
          if (iterator.next() == this) {
            iterator.remove();
            break;
          }
        }
      }
    }
  }
}
