package dev.lifus.janetreborn.service.combat;

import java.util.Objects;

public final class CombatGate {
  private Object owner;
  private CombatAction action;
  private int priority;
  private long tick;
  private long expiresAt;
  private boolean actedThisTick;

  public void tick() {
    tick++;
    actedThisTick = false;
    if (owner != null && tick > expiresAt) clear();
  }

  public boolean acquire(
      Object requester, CombatAction requestedAction, int requestedPriority, int keepTicks) {
    Objects.requireNonNull(requester, "requester");
    Objects.requireNonNull(requestedAction, "requestedAction");
    if (owner != null && owner != requester && requestedPriority <= priority) return false;
    owner = requester;
    action = requestedAction;
    priority = requestedPriority;
    expiresAt = tick + Math.max(1, keepTicks);
    return true;
  }

  public boolean owns(Object requester) {
    return owner == requester;
  }

  public boolean markAction(Object requester) {
    if (owner != requester || actedThisTick) return false;
    actedThisTick = true;
    return true;
  }

  public void noteVanillaAction() {
    actedThisTick = true;
  }

  public CombatAction activeAction() {
    return action;
  }

  public void release(Object requester) {
    if (owner == requester) clear();
  }

  private void clear() {
    owner = null;
    action = null;
    priority = Integer.MIN_VALUE;
    expiresAt = 0;
  }
}
