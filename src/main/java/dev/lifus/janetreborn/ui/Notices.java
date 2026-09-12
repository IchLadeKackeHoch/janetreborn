package dev.lifus.janetreborn.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class Notices {
  static final long DISPLAY_NANOS = 3_500_000_000L;
  private static final int MAX_PER_CHANNEL = 5;
  private final ConcurrentLinkedQueue<Notification> pending = new ConcurrentLinkedQueue<>();
  private final List<Notification> active = new ArrayList<>();
  private final AtomicLong ids = new AtomicLong();
  private final LongSupplier nanoTime;
  private volatile long displayNanos = DISPLAY_NANOS;

  public Notices() {
    this(System::nanoTime);
  }

  Notices(LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  public void gui(String title, String message, Type type) {
    post(Channel.GUI, title, message, type);
  }

  public void hud(String title, String message) {
    post(Channel.HUD, title, message, Type.SUCCESS);
  }

  public void hud(String title, String message, Type type) {
    post(Channel.HUD, title, message, type);
  }

  public void setDisplayMillis(int milliseconds) {
    displayNanos = Math.max(1, milliseconds) * 1_000_000L;
  }

  private void post(Channel channel, String title, String message, Type type) {
    long now = nanoTime.getAsLong();
    pending.add(
        new Notification(
            ids.incrementAndGet(),
            channel,
            Objects.requireNonNull(title, "title"),
            Objects.requireNonNull(message, "message"),
            Objects.requireNonNull(type, "type"),
            now,
            now + displayNanos));
  }

  synchronized List<Notification> visible(Channel channel) {
    refresh();
    return active.stream().filter(item -> item.channel() == channel).toList();
  }

  synchronized boolean hasVisible(boolean includeGui) {
    refresh();
    return active.stream()
        .anyMatch(
            item -> item.channel() == Channel.HUD || includeGui && item.channel() == Channel.GUI);
  }

  private void refresh() {
    long now = nanoTime.getAsLong();
    Notification incoming;
    while ((incoming = pending.poll()) != null) {
      Channel incomingChannel = incoming.channel();
      while (active.stream().filter(item -> item.channel() == incomingChannel).count()
          >= MAX_PER_CHANNEL) {
        removeOldest(incomingChannel);
      }
      active.add(incoming);
    }
    active.removeIf(item -> item.expiresAt() <= now);
  }

  private void removeOldest(Channel channel) {
    for (int i = 0; i < active.size(); i++) {
      if (active.get(i).channel() == channel) {
        active.remove(i);
        return;
      }
    }
  }

  public enum Channel {
    GUI,
    HUD
  }

  public enum Type {
    SUCCESS(0.35f, 0.90f, 0.48f),
    INFO(0.48f, 0.72f, 1.00f),
    WARNING(1.00f, 0.72f, 0.30f),
    ERROR(1.00f, 0.38f, 0.42f);

    private final float red;
    private final float green;
    private final float blue;

    Type(float red, float green, float blue) {
      this.red = red;
      this.green = green;
      this.blue = blue;
    }

    public float red() {
      return red;
    }

    public float green() {
      return green;
    }

    public float blue() {
      return blue;
    }
  }

  public record Notification(
      long id,
      Channel channel,
      String title,
      String message,
      Type type,
      long createdAt,
      long expiresAt) {}
}
