package dev.lifus.janetreborn.feature.combat.aim;

import dev.lifus.janetreborn.feature.combat.aim.VisibleHitPointSelector.HitPoint;
import dev.lifus.janetreborn.service.friend.FriendStore;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class AimTargetSelector {
  private static final float MAXIMUM_NEW_TARGET_ERROR = 45.0f;

  private final Minecraft minecraft;
  private final FriendStore friends;
  private final VisibleHitPointSelector hitPoints;

  public AimTargetSelector(
      Minecraft minecraft, FriendStore friends, VisibleHitPointSelector hitPoints) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.friends = Objects.requireNonNull(friends, "friends");
    this.hitPoints = Objects.requireNonNull(hitPoints, "hitPoints");
  }

  public Candidate select(LivingEntity current, HitPoint preferredPoint, float maximumRange) {
    return select(current, preferredPoint, maximumRange, MAXIMUM_NEW_TARGET_ERROR);
  }

  public Candidate select(
      LivingEntity current, HitPoint preferredPoint, float maximumRange, float acquisitionFov) {
    if (minecraft.player == null || minecraft.level == null) return null;
    Vec3 eye = minecraft.player.getEyePosition();
    Candidate retained = evaluate(current, preferredPoint, eye, maximumRange, false);
    if (retained != null) return retained;

    Candidate best = null;
    for (Entity entity : minecraft.level.entitiesForRendering()) {
      if (!(entity instanceof Player player)) continue;
      Candidate candidate = evaluate(player, null, eye, maximumRange, true, acquisitionFov);
      if (candidate != null && (best == null || candidate.score() < best.score())) best = candidate;
    }
    return best;
  }

  public Candidate evaluateCurrent(
      LivingEntity target, HitPoint preferredPoint, float maximumRange) {
    if (minecraft.player == null || minecraft.level == null) return null;
    return evaluate(target, preferredPoint, minecraft.player.getEyePosition(), maximumRange, false);
  }

  private Candidate evaluate(
      LivingEntity entity, HitPoint preferred, Vec3 eye, float maximumRange, boolean acquiring) {
    return evaluate(entity, preferred, eye, maximumRange, acquiring, MAXIMUM_NEW_TARGET_ERROR);
  }

  private Candidate evaluate(
      LivingEntity entity,
      HitPoint preferred,
      Vec3 eye,
      float maximumRange,
      boolean acquiring,
      float acquisitionFov) {
    if (!eligible(entity)) return null;
    double distance = eye.distanceTo(entity.getBoundingBox().getCenter());
    if (distance > maximumRange) return null;
    HitPoint hitPoint =
        hitPoints.select(
            entity, eye, minecraft.player.getYRot(), minecraft.player.getXRot(), preferred);
    if (hitPoint == null) return null;
    AimRotation rotation =
        AimMath.rotationsTo(
            eye.x,
            eye.y,
            eye.z,
            hitPoint.position().x,
            hitPoint.position().y,
            hitPoint.position().z);
    float angularError =
        AimMath.angularDistance(minecraft.player.getYRot(), minecraft.player.getXRot(), rotation);
    if (acquiring && angularError > acquisitionFov) return null;
    return new Candidate(entity, hitPoint, rotation, angularError + distance * 0.01);
  }

  private boolean eligible(LivingEntity entity) {
    return entity instanceof Player player
        && player != minecraft.player
        && player.isAlive()
        && !player.isDeadOrDying()
        && !player.isSpectator()
        && !friends.isProtected(player)
        && minecraft.player.canAttack(player);
  }

  public record Candidate(
      LivingEntity entity, HitPoint hitPoint, AimRotation rotation, double score) {}
}
