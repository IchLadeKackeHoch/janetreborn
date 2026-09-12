package dev.lifus.janetreborn.service.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantments;

public final class ItemScore {
  private ItemScore() {}

  public static double score(ItemStack stack, EquipmentSlot equipment) {
    ItemAttributeModifiers attributes = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
    double score = stack.getMaxDamage() * 3d + stack.getCount() * 0.001d;
    if (attributes != null) {
      score += attributes.compute(Attributes.ATTACK_DAMAGE, 0, equipment) * 1_000d;
      score += attributes.compute(Attributes.ATTACK_SPEED, 0, equipment) * 10d;
      score += attributes.compute(Attributes.MINING_EFFICIENCY, 0, equipment) * 100d;
    }
    Tool tool = stack.get(DataComponents.TOOL);
    if (tool != null) {
      score +=
          tool.rules().stream()
                  .flatMap(rule -> rule.speed().stream())
                  .mapToDouble(Float::doubleValue)
                  .max()
                  .orElse(tool.defaultMiningSpeed())
              * 100d;
    }
    score +=
        stack.getEnchantments().entrySet().stream()
            .mapToDouble(
                entry -> {
                  int level = entry.getIntValue();
                  if (entry.getKey().is(EnchantmentTags.CURSE)) return -10_000d * level;
                  if (entry.getKey().is(Enchantments.SHARPNESS)) return 500d * level;
                  return 25d * level;
                })
            .sum();
    if (stack.isDamageableItem()) {
      score += (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage();
    }
    return score;
  }

  public static double foodScore(ItemStack stack) {
    if (stack.is(Items.GOLDEN_CARROT)) return 1_000_000d + stack.getCount();
    var food = stack.get(DataComponents.FOOD);
    if (food == null) return Double.NEGATIVE_INFINITY;
    return food.nutrition() * 100d + food.saturation() * 10d + stack.getCount() * 0.001d;
  }
}
