package dev.lifus.janetreborn.feature.combat;

import dev.lifus.janetreborn.platform.minecraft.mixin.MinecraftAccessor;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.friend.FriendStore;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class TriggerbotModule extends Module {
  private static final int COORDINATOR_PRIORITY = 60;
  private final Minecraft minecraft;
  private final CombatGate coordinator;
  private final FriendStore friends;
  private final BoolSetting playersOnly = add(new BoolSetting("Players Only", true));
  private final BoolSetting preferCriticals =
      add(
          new BoolSetting("prefer_criticals", "Wait for Airborne Criticals", true)
              .description("Briefly waits for a critical only when you are already airborne."));
  private final BoolSetting weaponsOnly = add(new BoolSetting("Weapons Only", true));
  private final RangeSetting<Integer> reactionDelay =
      add(
          new RangeSetting<>("Reaction Delay", new Range<>(5, 35), 0, 250, 1)
              .legacyBounds("minimum_delay", "maximum_delay")
              .unit("ms"));
  private LivingEntity target;
  private long attackAt;

  public TriggerbotModule(Minecraft minecraft, CombatGate coordinator, FriendStore friends) {
    super("Triggerbot", "Automatically attacks through the vanilla input path", Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.friends = Objects.requireNonNull(friends, "friends");
  }

  @Override
  protected void onDisable() {
    coordinator.release(this);
    reset();
  }

  public void tryVanillaAttack() {
    if (!isEnabled() || !readyForAutomaticAttack()) {
      reset();
      return;
    }
    LivingEntity entity = currentTarget();
    if (entity == null || !valid(entity) || !canAttack(entity)) {
      reset();
      return;
    }
    if (minecraft.player.getAttackStrengthScale(0.5f) <= 0.9f
        || preferCriticals.getValue() && waitsForCritical()) {
      reset();
      return;
    }
    long now = System.nanoTime();
    if (target != entity) {
      target = entity;
      attackAt = now + delay() * 1_000_000L;
    }
    if (now < attackAt) return;
    if (!coordinator.acquire(this, CombatAction.TRIGGERBOT, COORDINATOR_PRIORITY, 1)
        || !coordinator.markAction(this)) {
      coordinator.release(this);
      return;
    }
    try {
      ((MinecraftAccessor) minecraft).janetReborn$startAttack();
    } finally {
      coordinator.release(this);
      reset();
    }
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
        && !minecraft.options.keyPickItem.isDown()
        && !minecraft.options.keySwapOffhand.isDown()
        && !minecraft.options.keyDrop.isDown();
  }

  private LivingEntity currentTarget() {
    if (!(minecraft.hitResult instanceof EntityHitResult hit)
        || !(hit.getEntity() instanceof LivingEntity entity)) return null;
    return entity;
  }

  private boolean valid(LivingEntity entity) {
    return minecraft.player != null
        && minecraft.gameMode != null
        && entity.isAlive()
        && !entity.isDeadOrDying()
        && (!playersOnly.getValue() || entity instanceof Player)
        && (!(entity instanceof Player player) || !friends.isProtected(player))
        && (!(entity instanceof Player player) || !isShieldBlocking(player))
        && minecraft.player.canAttack(entity)
        && minecraft.player.isWithinEntityInteractionRange(entity, 0);
  }

  private boolean canAttack(LivingEntity entity) {
    if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != entity)
      return false;
    ItemStack stack = minecraft.player.getMainHandItem();
    if (!stack.isItemEnabled(minecraft.level.enabledFeatures())
        || minecraft.player.cannotAttackWithItem(stack, 0)
        || weaponsOnly.getValue() && stack.get(DataComponents.WEAPON) == null) return false;
    AttackRange range = stack.get(DataComponents.ATTACK_RANGE);
    return range == null || range.isInRange(minecraft.player, hit.getLocation());
  }

  private static boolean isShieldBlocking(Player player) {
    ItemStack blockingItem = player.getItemBlockingWith();
    return player.isBlocking() && blockingItem != null && blockingItem.is(Items.SHIELD);
  }

  private boolean waitsForCritical() {
    Player player = minecraft.player;
    if (player.onGround() || isInsideCobweb(player)) return false;
    if (player.onClimbable()
        || player.isInWater()
        || player.isMobilityRestricted()
        || player.isPassenger()
        || player.isSprinting()) return false;
    return player.fallDistance <= 0;
  }

  private boolean isInsideCobweb(Player player) {
    AABB bounds = player.getBoundingBox().deflate(1.0E-4);
    BlockPos minimum = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
    BlockPos maximum = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
    for (BlockPos position : BlockPos.betweenClosed(minimum, maximum)) {
      if (minecraft.level.getBlockState(position).is(Blocks.COBWEB)) return true;
    }
    return false;
  }

  private int delay() {
    int min = reactionDelay.getValue().minimum();
    int max = reactionDelay.getValue().maximum();
    return ThreadLocalRandom.current().nextInt(min, max + 1);
  }

  private void reset() {
    target = null;
    attackAt = 0;
  }
}
