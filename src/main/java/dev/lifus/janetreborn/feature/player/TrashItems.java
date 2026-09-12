package dev.lifus.janetreborn.feature.player;

import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class TrashItems {
  private static final Set<Item> ITEMS =
      Set.of(
          Items.ROTTEN_FLESH,
          Items.POISONOUS_POTATO,
          Items.SPIDER_EYE,
          Items.BOWL,
          Items.GLASS_BOTTLE,
          Items.WHEAT_SEEDS,
          Items.BEETROOT_SEEDS,
          Items.MELON_SEEDS,
          Items.PUMPKIN_SEEDS,
          Items.BONE,
          Items.INK_SAC,
          Items.GLOW_INK_SAC);

  private TrashItems() {}

  static boolean contains(ItemStack stack) {
    return contains(stack.getItem());
  }

  static boolean contains(Item item) {
    return ITEMS.contains(item);
  }
}
