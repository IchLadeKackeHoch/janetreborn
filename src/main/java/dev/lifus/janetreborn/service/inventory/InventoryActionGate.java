package dev.lifus.janetreborn.service.inventory;

import java.util.Objects;

public final class InventoryActionGate {
  private Object owner;
  private int priority = Integer.MIN_VALUE;
  private long tick;
  private long expiresAt;
  private boolean actedThisTick;

  public void tick() {
    tick++;
    actedThisTick = false;
    if (owner != null && tick > expiresAt) clear();
  }

  public boolean acquire(Object requester, int requestedPriority, int keepTicks) {
    Objects.requireNonNull(requester, "requester");
    if (owner != null && owner != requester && requestedPriority <= priority) return false;
    owner = requester;
    priority = requestedPriority;
    expiresAt = tick + Math.max(1, keepTicks);
    return true;
  }

  public boolean markAction(Object requester) {
    if (owner != requester || actedThisTick) return false;
    actedThisTick = true;
    return true;
  }

  public void release(Object requester) {
    if (owner == requester) clear();
  }

  private void clear() {
    owner = null;
    priority = Integer.MIN_VALUE;
    expiresAt = 0;
  }
}
