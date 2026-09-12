package dev.lifus.janetreborn.feature.player;

import dev.lifus.janetreborn.service.inventory.ScaffoldPolicy;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public enum HotbarCategory {
  SWORD("sword"),
  WEAPON("weapon"),
  SPEAR("spear"),
  MACE("mace"),
  BOW("bow"),
  CROSSBOW("crossbow"),
  AXE("axe"),
  PICKAXE("pickaxe"),
  SHOVEL("shovel"),
  HOE("hoe"),
  ROD("rod"),
  SHIELD("shield"),
  WATER("water"),
  LAVA("lava"),
  MILK("milk"),
  PEARL("pearl"),
  GAPPLE("gapple"),
  FOOD("food"),
  POTION("potion"),
  BLOCK("block"),
  THROWABLES("throwables"),
  IGNORE("ignore"),
  NONE("none"),
  COBWEB("cobweb"),
  BUCKET("bucket");

  private final String name;

  HotbarCategory(String name) {
    this.name = name;
  }

  public boolean matches(ItemStack stack) {
    return switch (this) {
      case SWORD -> stack.is(ItemTags.SWORDS);
      case WEAPON ->
          stack.is(ItemTags.SWORDS)
              || stack.is(ItemTags.AXES)
              || stack.is(ItemTags.SPEARS)
              || stack.is(Items.MACE);
      case SPEAR -> stack.is(ItemTags.SPEARS);
      case MACE -> stack.is(Items.MACE);
      case BOW -> stack.is(Items.BOW);
      case CROSSBOW -> stack.is(Items.CROSSBOW);
      case AXE -> stack.is(ItemTags.AXES);
      case PICKAXE -> stack.is(ItemTags.PICKAXES);
      case SHOVEL -> stack.is(ItemTags.SHOVELS);
      case HOE -> stack.is(ItemTags.HOES);
      case ROD -> stack.is(Items.FISHING_ROD);
      case SHIELD -> stack.is(Items.SHIELD);
      case WATER -> stack.is(Items.WATER_BUCKET);
      case LAVA -> stack.is(Items.LAVA_BUCKET);
      case MILK -> stack.is(Items.MILK_BUCKET);
      case PEARL -> stack.is(Items.ENDER_PEARL);
      case GAPPLE -> stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
      case FOOD -> stack.get(DataComponents.FOOD) != null;
      case POTION ->
          stack.is(Items.POTION)
              || stack.is(Items.SPLASH_POTION)
              || stack.is(Items.LINGERING_POTION);
      case BLOCK -> ScaffoldPolicy.isSafe(stack);
      case THROWABLES ->
          stack.is(Items.SNOWBALL)
              || stack.is(Items.EGG)
              || stack.is(Items.EXPERIENCE_BOTTLE)
              || stack.is(Items.WIND_CHARGE);
      case COBWEB -> stack.is(Items.COBWEB);
      case BUCKET -> stack.is(Items.BUCKET);
      case IGNORE, NONE -> false;
    };
  }

  public String getName() {
    return name;
  }

  public boolean selectable() {
    return this != IGNORE && this != NONE;
  }
}
