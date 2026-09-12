package dev.lifus.janetreborn.feature.combat;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.platform.minecraft.mixin.MinecraftAccessor;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.Hotbar;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;

public final class ShieldBreakerModule extends Module {
  private static final int COORDINATOR_PRIORITY = 90;
  private final Minecraft minecraft;
  private final Hotbar hotbar;
  private final CombatGate coordinator;
  private final FriendStore friends;
  private final BoolSetting playersOnly = add(new BoolSetting("Players Only", true));
  private final ModeSetting<Activation> activation =
      add(
          new ModeSetting<>("Activation", Activation.BOTH)
              .description(
                  "Choose whether shield disabling applies to manual hits, automatic hits, or both."));
  private boolean automaticAttack;

  @Subscribe private final Listener<TickEvent> clientTick = this::tick;

  public ShieldBreakerModule(
      Minecraft minecraft, Hotbar hotbar, CombatGate coordinator, FriendStore friends) {
    super(
        "ShieldDisabler",
        "Automatically uses a hotbar axe against a raised shield",
        Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.friends = Objects.requireNonNull(friends, "friends");
  }

  @Override
  protected void onDisable() {
    coordinator.release(this);
  }

  private void tick(TickEvent ignored) {
    if (activation.getValue() == Activation.MANUAL_HITS) {
      coordinator.release(this);
      return;
    }
    if (!readyForAutomaticAttack() || currentShieldTarget() == null || findUsableAxe() < 0) {
      coordinator.release(this);
      return;
    }
    if (!coordinator.acquire(this, CombatAction.SHIELD_DISABLE, COORDINATOR_PRIORITY, 1)
        || !coordinator.markAction(this)) {
      coordinator.release(this);
      return;
    }
    try {
      automaticAttack = true;
      ((MinecraftAccessor) minecraft).janetReborn$startAttack();
    } finally {
      automaticAttack = false;
      coordinator.release(this);
    }
  }

  public void prepareVanillaAttack() {
    if (!isEnabled()
        || !automaticAttack && activation.getValue() == Activation.AUTOMATIC
        || currentShieldTarget() == null) return;
    int axeSlot = findUsableAxe();
    if (axeSlot < 0) return;
    hotbar.selectTemporarily(axeSlot);
  }

  private boolean readyForAutomaticAttack() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.gui.screen() == null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isSpectator()
        && !minecraft.gameMode.isDestroying()
        && !minecraft.player.isPassenger()
        && !minecraft.player.isHandsBusy()
        && !minecraft.player.isUsingItem()
        && !minecraft.options.keyAttack.isDown()
        && !minecraft.options.keyUse.isDown()
        && minecraft.player.getAttackStrengthScale(0.5f) > 0.9f;
  }

  private LivingEntity currentShieldTarget() {
    if (!(minecraft.hitResult instanceof EntityHitResult hit)
        || !(hit.getEntity() instanceof LivingEntity entity)
        || !valid(entity)) return null;
    return entity;
  }

  private boolean valid(LivingEntity entity) {
    return minecraft.player != null
        && minecraft.level != null
        && entity.isAlive()
        && !entity.isDeadOrDying()
        && (!playersOnly.getValue() || entity instanceof Player)
        && (!(entity instanceof Player player) || !friends.isProtected(player))
        && entity.isBlocking()
        && entity.getItemBlockingWith() != null
        && entity.getItemBlockingWith().getItem() == Items.SHIELD
        && minecraft.player.isWithinEntityInteractionRange(entity, 0)
        && facesAttacker(entity);
  }

  private boolean facesAttacker(LivingEntity entity) {
    Vec3 direction = minecraft.player.position().subtract(entity.position());
    direction = new Vec3(direction.x, 0, direction.z).normalize();
    return direction.dot(entity.calculateViewVector(0, entity.getYHeadRot())) >= 0;
  }

  private int findUsableAxe() {
    return hotbar.find(this::usableAxe);
  }

  private boolean usableAxe(ItemStack stack) {
    return stack.typeHolder().is(ItemTags.AXES)
        && stack.isItemEnabled(minecraft.level.enabledFeatures())
        && stack.get(DataComponents.PIERCING_WEAPON) == null;
  }

  private enum Activation {
    MANUAL_HITS,
    AUTOMATIC,
    BOTH
  }
}
