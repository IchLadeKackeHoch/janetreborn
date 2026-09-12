package dev.lifus.janetreborn.service.placement;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

public final class FluidReservations {
  private final Map<BlockPos, Object> owners = new HashMap<>();

  public boolean acquire(Object owner, BlockPos position) {
    Objects.requireNonNull(owner, "owner");
    BlockPos key = Objects.requireNonNull(position, "position").immutable();
    Object current = owners.get(key);
    if (current != null && current != owner) return false;
    owners.put(key, owner);
    return true;
  }

  public boolean owns(Object owner, BlockPos position) {
    return owners.get(Objects.requireNonNull(position, "position")) == owner;
  }

  public boolean isOwnedByOther(Object owner, BlockPos position) {
    Object current = owners.get(Objects.requireNonNull(position, "position"));
    return current != null && current != owner;
  }

  public void release(Object owner, BlockPos position) {
    Objects.requireNonNull(position, "position");
    owners.computeIfPresent(position, (ignored, current) -> current == owner ? null : current);
  }

  public void releaseAll(Object owner) {
    for (Iterator<Map.Entry<BlockPos, Object>> iterator = owners.entrySet().iterator();
        iterator.hasNext(); ) {
      if (iterator.next().getValue() == owner) iterator.remove();
    }
  }

  public void clear() {
    owners.clear();
  }
}
