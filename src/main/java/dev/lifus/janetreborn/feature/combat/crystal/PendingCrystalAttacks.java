package dev.lifus.janetreborn.feature.combat.crystal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

final class PendingCrystalAttacks {
  private final Map<Integer, Long> pending = new HashMap<>();

  void add(int entityId, long tick, int timeout) {
    pending.put(entityId, tick + timeout);
  }

  void update(long tick, IntPredicate stillPresent) {
    pending
        .entrySet()
        .removeIf(entry -> tick >= entry.getValue() || !stillPresent.test(entry.getKey()));
  }

  boolean contains(int entityId) {
    return pending.containsKey(entityId);
  }

  void clear() {
    pending.clear();
  }
}
