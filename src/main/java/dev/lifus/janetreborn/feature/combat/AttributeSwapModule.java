package dev.lifus.janetreborn.feature.combat;

import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.EntityHitResult;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;

public final class AttributeSwapModule extends Module {
  private final Minecraft minecraft;
  private final Hotbar hotbar;
  private final FriendStore friends;
  private final ModeSetting<Source> swapFrom = add(new ModeSetting<>("Swap From", Source.SWORD));
  private final ModeSetting<Weapon> swapTo = add(new ModeSetting<>("Swap To", Weapon.MACE));
  private final BoolSetting playersOnly = add(new BoolSetting("Players Only", true));
  private final BoolSetting requireCharged =
      add(
          new BoolSetting("require_charged", "Require Full Attack Cooldown", true)
              .description("Only swaps when the vanilla attack meter is ready."));
  private final BoolSetting smashOnly = add(new BoolSetting("smash_only", "Mace Smash Only", true));
  private final BoolSetting skipShields = add(new BoolSetting("Skip Shields", true));

  public AttributeSwapModule(Minecraft minecraft, Hotbar hotbar, FriendStore friends) {
    super(
        "AttributeSwap",
        "Swaps weapons immediately before an attack to combine their attributes",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.friends = Objects.requireNonNull(friends, "friends");
    smashOnly.visibleWhen(() -> swapTo.getValue() == Weapon.MACE);
  }

  public void prepareAttack() {
    if (!isEnabled() || !ready()) return;
    LivingEntity target = target();
    if (target == null || !validTarget(target)) return;

    ItemStack held = minecraft.player.getMainHandItem();
    Weapon destination = swapTo.getValue();
    if (!swapFrom.getValue().matches(held)
        || destination.matches(held)
        || requireCharged.getValue() && minecraft.player.getAttackStrengthScale(0.5f) <= 0.9f
        || destination == Weapon.MACE
            && smashOnly.getValue()
            && !MaceItem.canSmashAttack(minecraft.player)
        || skipShields.getValue() && isShieldBlocking(target)) return;

    int slot = hotbar.find(stack -> destination.matches(stack) && usable(stack));
    if (slot >= 0) hotbar.selectTemporarily(slot);
  }

  private boolean ready() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.gui.screen() == null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.player.isPassenger()
        && !minecraft.player.isHandsBusy()
        && !minecraft.player.isUsingItem();
  }

  private LivingEntity target() {
    return minecraft.hitResult instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity entity
        ? entity
        : null;
  }

  private boolean validTarget(LivingEntity target) {
    return target != minecraft.player
        && target.isAlive()
        && !target.isDeadOrDying()
        && (!playersOnly.getValue() || target instanceof Player)
        && (!(target instanceof Player player) || !friends.isProtected(player))
        && minecraft.player.canAttack(target)
        && minecraft.player.isWithinEntityInteractionRange(target, 0.0);
  }

  private boolean usable(ItemStack stack) {
    return !stack.isEmpty() && stack.isItemEnabled(minecraft.level.enabledFeatures());
  }

  private static boolean isShieldBlocking(LivingEntity target) {
    ItemStack blockingItem = target.getItemBlockingWith();
    return target.isBlocking() && blockingItem != null && blockingItem.is(Items.SHIELD);
  }

  private enum Source {
    ANY_WEAPON,
    SWORD,
    AXE,
    SPEAR,
    MACE;

    private boolean matches(ItemStack stack) {
      return switch (this) {
        case ANY_WEAPON ->
            stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SPEARS)
                || stack.is(Items.MACE);
        case SWORD -> stack.is(ItemTags.SWORDS);
        case AXE -> stack.is(ItemTags.AXES);
        case SPEAR -> stack.is(ItemTags.SPEARS);
        case MACE -> stack.is(Items.MACE);
      };
    }
  }

  private enum Weapon {
    SWORD,
    AXE,
    SPEAR,
    MACE;

    private boolean matches(ItemStack stack) {
      return switch (this) {
        case SWORD -> stack.is(ItemTags.SWORDS);
        case AXE -> stack.is(ItemTags.AXES);
        case SPEAR -> stack.is(ItemTags.SPEARS);
        case MACE -> stack.is(Items.MACE);
      };
    }
  }
}
