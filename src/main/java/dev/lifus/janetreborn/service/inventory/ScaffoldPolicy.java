package dev.lifus.janetreborn.service.inventory;

import java.util.Set;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.FallingBlock;

public final class ScaffoldPolicy {
  private static final Set<Item> UNSAFE_ITEMS =
      Set.of(
          Items.TNT,
          Items.SOUL_SAND,
          Items.SOUL_SOIL,
          Items.MAGMA_BLOCK,
          Items.CACTUS,
          Items.CAMPFIRE,
          Items.SOUL_CAMPFIRE,
          Items.COBWEB,
          Items.SCAFFOLDING);

  private ScaffoldPolicy() {}

  public static boolean isSafe(ItemStack stack) {
    return stack.getItem() instanceof BlockItem blockItem
        && !(blockItem.getBlock() instanceof FallingBlock)
        && !UNSAFE_ITEMS.contains(stack.getItem());
  }
}
