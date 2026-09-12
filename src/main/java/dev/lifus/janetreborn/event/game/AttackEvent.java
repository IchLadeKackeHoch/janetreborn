package dev.lifus.janetreborn.event.game;

import dev.codeman.eventbusx.Event;
import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class AttackEvent extends Event {
  private final Entity target;
  private final Vec3 hitPosition;

  public AttackEvent(Entity target) {
    this(target, Objects.requireNonNull(target, "target").getBoundingBox().getCenter());
  }

  public AttackEvent(Entity target, Vec3 hitPosition) {
    this.target = Objects.requireNonNull(target, "target");
    this.hitPosition = Objects.requireNonNull(hitPosition, "hitPosition");
  }

  public Entity getTarget() {
    return target;
  }

  public Vec3 getHitPosition() {
    return hitPosition;
  }
}
