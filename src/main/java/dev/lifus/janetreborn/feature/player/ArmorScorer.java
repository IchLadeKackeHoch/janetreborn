package dev.lifus.janetreborn.feature.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.EquipmentAssets;

final class ArmorScorer {
  private ArmorScorer() {}

  static double score(ItemStack stack, EquipmentSlot slot) {
    ItemAttributeModifiers attributes = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
    double armor = 0;
    if (attributes != null) {
      armor = attributes.compute(Attributes.ARMOR, 0, slot);
    }

    int protectionLevel =
        stack.getEnchantments().entrySet().stream()
            .filter(entry -> entry.getKey().is(Enchantments.PROTECTION))
            .mapToInt(entry -> entry.getIntValue())
            .max()
            .orElse(0);
    return protectionScore(armor, protectionLevel, materialTier(stack));
  }

  static boolean hasBindingCurse(ItemStack stack) {
    return stack.getEnchantments().entrySet().stream()
        .anyMatch(entry -> entry.getKey().is(Enchantments.BINDING_CURSE));
  }

  static double protectionScore(double armor, int protectionLevel, int materialTier) {
    return (armor + Math.max(0, protectionLevel)) * 100d + Math.max(0, materialTier);
  }

  private static int materialTier(ItemStack stack) {
    var equippable = stack.get(DataComponents.EQUIPPABLE);
    if (equippable == null || equippable.assetId().isEmpty()) return 0;
    var asset = equippable.assetId().orElseThrow();
    if (asset.equals(EquipmentAssets.NETHERITE)) return 8;
    if (asset.equals(EquipmentAssets.DIAMOND)) return 7;
    if (asset.equals(EquipmentAssets.TURTLE_SCUTE)) return 6;
    if (asset.equals(EquipmentAssets.IRON)) return 5;
    if (asset.equals(EquipmentAssets.CHAINMAIL)) return 4;
    if (asset.equals(EquipmentAssets.COPPER)) return 3;
    if (asset.equals(EquipmentAssets.GOLD)) return 2;
    if (asset.equals(EquipmentAssets.LEATHER)) return 1;
    return 0;
  }
}
