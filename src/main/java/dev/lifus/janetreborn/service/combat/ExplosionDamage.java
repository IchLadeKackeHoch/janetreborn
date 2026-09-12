package dev.lifus.janetreborn.service.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

public final class ExplosionDamage {
  private ExplosionDamage() {}

  public static float estimateSelf(Minecraft minecraft, Vec3 position, Entity source) {
    if (minecraft.player == null || minecraft.level == null) return Float.POSITIVE_INFINITY;
    double distance = position.distanceTo(minecraft.player.position()) / 12.0;
    if (distance > 1.0) return 0.0f;
    double exposure = ServerExplosion.getSeenPercent(position, minecraft.player);
    double impact = (1.0 - distance) * exposure;
    float raw = (float) ((impact * impact + impact) * 42.0 + 1.0);
    float damage =
        CombatRules.getDamageAfterAbsorb(
            minecraft.player,
            raw,
            minecraft.level.damageSources().explosion(source, null),
            minecraft.player.getArmorValue(),
            (float) minecraft.player.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    var resistance = minecraft.player.getEffect(MobEffects.RESISTANCE);
    if (resistance != null) {
      damage *= Math.max(0.0f, 1.0f - (resistance.getAmplifier() + 1) * 0.2f);
    }
    return damage;
  }
}
