package dev.lifus.janetreborn.feature.combat.totem;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Priority;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.PostInputEvent;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.event.game.TotemActivatedEvent;
import dev.lifus.janetreborn.platform.minecraft.mixin.AbstractContainerScreenAccessor;
import dev.lifus.janetreborn.service.combat.CombatAction;
import dev.lifus.janetreborn.service.combat.CombatGate;
import dev.lifus.janetreborn.service.combat.ExplosionDamage;
import dev.lifus.janetreborn.service.friend.FriendStore;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.service.inventory.InventoryGuard;
import dev.lifus.janetreborn.service.inventory.SlotPathing;
import dev.lifus.janetreborn.service.inventory.SlotPathing.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class TotemGuardModule extends Module {
  private static final int COMBAT_PRIORITY = 200;
  private static final int INVENTORY_PRIORITY = 100;
  private static final int CONFIRMATION_TIMEOUT = 12;
  private static final int EMERGENCY_DURATION = 40;
  private final Minecraft minecraft;
  private final CombatGate combat;
  private final InventoryActionGate inventoryActions;
  private final FriendStore friends;
  private final TotemSequence sequence = new TotemSequence();
  private final BoolSetting maintainOffhand = add(new BoolSetting("Maintain Offhand", true));
  private final BoolSetting restockHotbar = add(new BoolSetting("Restock Hotbar", true));
  private final NumberSetting<Integer> preferredHotbarSlot =
      add(new NumberSetting<>("Preferred Hotbar Slot", 9, 1, 9, 1));
  private final BoolSetting replaceOccupied = add(new BoolSetting("Replace Occupied Slot", false));
  private final BoolSetting restorePrevious =
      add(new BoolSetting("restore_previous_slot", "Restore Slot After Equip", true));
  private final NumberSetting<Integer> switchDelay =
      add(new NumberSetting<>("Switch Delay", 1, 0, 10, 1).unit("ticks").markAdvanced());
  private final NumberSetting<Integer> equipDelay =
      add(new NumberSetting<>("Equip Delay", 1, 0, 10, 1).unit("ticks").markAdvanced());
  private final RangeSetting<Integer> inventoryDelay =
      add(
          new RangeSetting<>("Inventory Action Delay", new Range<>(35, 90), 0, 250, 1)
              .unit("ms")
              .markAdvanced());
  private final BoolSetting emergencyMainHand = add(new BoolSetting("Emergency Main Hand", true));
  private final BoolSetting restoreAfterEmergency =
      add(new BoolSetting("Restore Slot After Emergency", true));
  private final NumberSetting<Double> healthTrigger =
      add(new NumberSetting<>("Health Trigger", 8.0, 1.0, 20.0, 0.5).unit("HP"));
  private final BoolSetting triggerAfterTotem =
      add(new BoolSetting("Trigger After Totem Use", true));
  private final BoolSetting predictCrystalDamage =
      add(new BoolSetting("Predict Crystal Damage", true));
  private final NumberSetting<Double> threatRadius =
      add(new NumberSetting<>("Threat Radius", 6.0, 2.0, 12.0, 0.5).unit("blocks").markAdvanced());
  private final BoolSetting requireNearbyEnemy = add(new BoolSetting("Require Nearby Enemy", true));
  private final BoolSetting predictVisiblePlacements =
      add(new BoolSetting("Predict Visible Placements", false));
  private final BoolSetting interruptActiveUse =
      add(new BoolSetting("Interrupt Active Item Use", false));
  private final BoolSetting noMove =
      add(new BoolSetting("no_move", "Pause While Moving", true).markAdvanced());
  private long tick;
  private long emergencyUntil;
  private int sourceSlot = -1;
  private int previousSlot = -1;
  private int emergencyPreviousSlot = -1;
  private boolean emergencyWasActive;
  private ResourceKey<Level> dimension;
  private boolean inventoryOpen;
  private int plannedMenuSlot = -1;
  private int plannedButton = -1;
  private long inventoryActionAt;
  private Point inventoryPath;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<TickEvent> clientTick = this::maintain;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<PostInputEvent> postInput = this::act;

  @Subscribe(priority = Priority.HIGH)
  private final Listener<TickEndEvent> tickEnd = this::restock;

  @Subscribe
  private final Listener<TotemActivatedEvent> totemActivated =
      ignored -> {
        if (triggerAfterTotem.getValue()) emergencyUntil = tick + EMERGENCY_DURATION;
      };

  @Subscribe private final Listener<ServerTeleportEvent> teleport = ignored -> reset();

  public TotemGuardModule(
      Minecraft minecraft,
      CombatGate combat,
      InventoryActionGate inventoryActions,
      FriendStore friends) {
    super("TotemGuard", "Keeps survival totems ready", Category.COMBAT);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.combat = Objects.requireNonNull(combat, "combat");
    this.inventoryActions = Objects.requireNonNull(inventoryActions, "inventoryActions");
    this.friends = Objects.requireNonNull(friends, "friends");
    preferredHotbarSlot.visibleWhen(restockHotbar);
    replaceOccupied.visibleWhen(restockHotbar);
    inventoryDelay.visibleWhen(() -> restockHotbar.getValue() || maintainOffhand.getValue());
    healthTrigger.visibleWhen(emergencyMainHand);
    restoreAfterEmergency.visibleWhen(emergencyMainHand);
    triggerAfterTotem.visibleWhen(emergencyMainHand);
    predictCrystalDamage.visibleWhen(emergencyMainHand);
    threatRadius.visibleWhen(() -> emergencyMainHand.getValue() && predictCrystalDamage.getValue());
    requireNearbyEnemy.visibleWhen(
        () -> emergencyMainHand.getValue() && predictCrystalDamage.getValue());
    predictVisiblePlacements.visibleWhen(
        () -> emergencyMainHand.getValue() && predictCrystalDamage.getValue());
  }

  @Override
  protected void onDisable() {
    if (emergencyWasActive && minecraft.player != null) restoreAfterEmergency();
    reset();
  }

  private void maintain(TickEvent ignored) {
    tick++;
    if (!connected()) {
      reset();
      return;
    }
    if (dimension != null && !dimension.equals(minecraft.level.dimension())) {
      reset();
      dimension = minecraft.level.dimension();
      return;
    }
    dimension = minecraft.level.dimension();
    if (sequence.expired(tick)) clearSequence();
    if (sequence.phase() == TotemSequence.Phase.CONFIRMATION
        && minecraft.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
      sequence.confirmed(tick, restorePrevious.getValue(), switchDelay.getValue());
    } else if (sequence.phase() != TotemSequence.Phase.IDLE
        && sequence.phase() != TotemSequence.Phase.RESTORE_DELAY
        && minecraft.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
      clearSequence();
    }

    boolean emergency = emergency();
    if (emergency) selectEmergencyTotem();
    else if (emergencyWasActive) restoreAfterEmergency();
    emergencyWasActive = emergency;
    if (sequence.phase() == TotemSequence.Phase.RESTORE_DELAY && sequence.ready(tick)) {
      if (!emergency
          && previousSlot >= 0
          && minecraft.player.getInventory().getSelectedSlot() == sourceSlot) {
        minecraft.player.getInventory().setSelectedSlot(previousSlot);
      }
      clearSequence();
    }

    if (minecraft.gui.screen() == null
        && maintainOffhand.getValue()
        && !minecraft.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
        && sequence.phase() == TotemSequence.Phase.IDLE
        && (!minecraft.player.isUsingItem() || interruptActiveUse.getValue())) {
      int slot = findHotbarTotem();
      if (slot >= 0) {
        sourceSlot = slot;
        previousSlot = minecraft.player.getInventory().getSelectedSlot();
        sequence.begin(
            tick,
            switchDelay.getValue(),
            switchDelay.getValue() + equipDelay.getValue() + CONFIRMATION_TIMEOUT);
      }
    }
  }

  private void act(PostInputEvent ignored) {
    if (!connected() || minecraft.gui.screen() != null) return;
    if (sequence.phase() == TotemSequence.Phase.SWITCH_DELAY && sequence.ready(tick)) {
      if (!minecraft.player.getInventory().getItem(sourceSlot).is(Items.TOTEM_OF_UNDYING)) {
        clearSequence();
        return;
      }
      minecraft.player.getInventory().setSelectedSlot(sourceSlot);
      sequence.selected(tick, equipDelay.getValue());
      return;
    }
    if (sequence.phase() != TotemSequence.Phase.EQUIP_DELAY || !sequence.ready(tick)) return;
    if (!minecraft.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
      clearSequence();
      return;
    }
    if (!combat.acquire(this, CombatAction.TOTEM_SAFETY, COMBAT_PRIORITY, 2)
        || !combat.markAction(this)
        || !inventoryActions.acquire(this, INVENTORY_PRIORITY, 2)
        || !inventoryActions.markAction(this)) {
      releaseControl();
      return;
    }
    minecraft.player.connection.send(
        new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ZERO,
            Direction.DOWN));
    sequence.swapped(tick, CONFIRMATION_TIMEOUT);
    releaseControl();
  }

  private void restock(TickEndEvent ignored) {
    if (!(minecraft.gui.screen() instanceof InventoryScreen screen)
        || !connected()
        || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
      resetInventoryPlan();
      return;
    }
    if (!inventoryOpen) {
      inventoryOpen = true;
      inventoryPath = mousePosition();
    }
    if (!InventoryGuard.mayClick(minecraft.player, noMove.getValue())
        || !minecraft.player.inventoryMenu.getCarried().isEmpty()) return;

    InventoryMenu menu = minecraft.player.inventoryMenu;
    int button = desiredDestinationButton();
    int source = button < 0 ? -1 : findMainInventoryTotem(menu);
    if (source < 0) {
      clearInventoryAction();
      return;
    }
    if (plannedMenuSlot != source || plannedButton != button) {
      plannedMenuSlot = source;
      plannedButton = button;
      Range<Integer> delay = inventoryDelay.getValue();
      inventoryActionAt =
          System.nanoTime()
              + SlotPathing.travelDelayMillis(
                      inventoryPath,
                      slotPosition(screen, menu.getSlot(source)),
                      layout(screen, menu),
                      delay.minimum(),
                      delay.maximum())
                  * 1_000_000L;
    }
    if (System.nanoTime() < inventoryActionAt) return;
    if (!inventoryActions.acquire(this, INVENTORY_PRIORITY, 1)) return;
    if (!inventoryActions.markAction(this)) {
      inventoryActions.release(this);
      return;
    }
    minecraft.gameMode.handleContainerInput(
        menu.containerId, source, button, ContainerInput.SWAP, minecraft.player);
    inventoryPath = slotPosition(screen, menu.getSlot(source));
    inventoryActions.release(this);
    clearInventoryAction();
  }

  private int desiredDestinationButton() {
    if (maintainOffhand.getValue()
        && !minecraft.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
      return Inventory.SLOT_OFFHAND;
    }
    if (!restockHotbar.getValue()) return -1;
    int slot = preferredHotbarSlot.getValue() - 1;
    var current = minecraft.player.getInventory().getItem(slot);
    if (current.is(Items.TOTEM_OF_UNDYING)) return -1;
    return current.isEmpty() || replaceOccupied.getValue() ? slot : -1;
  }

  private int findMainInventoryTotem(InventoryMenu menu) {
    for (int i = 0; i < menu.slots.size(); i++) {
      Slot slot = menu.getSlot(i);
      int inventorySlot = slot.getContainerSlot();
      if (slot.container == minecraft.player.getInventory()
          && inventorySlot >= Inventory.getSelectionSize()
          && inventorySlot < Inventory.INVENTORY_SIZE
          && slot.getItem().is(Items.TOTEM_OF_UNDYING)) return i;
    }
    return -1;
  }

  private boolean emergency() {
    if (!emergencyMainHand.getValue()) return false;
    double health = minecraft.player.getHealth() + minecraft.player.getAbsorptionAmount();
    return health <= healthTrigger.getValue()
        || tick <= emergencyUntil
        || predictCrystalDamage.getValue() && lethalCrystalThreat(health);
  }

  private boolean lethalCrystalThreat(double health) {
    double radius = threatRadius.getValue();
    if (requireNearbyEnemy.getValue() && !nearbyEnemy(radius)) return false;
    double radiusSquared = radius * radius;
    for (Entity entity : minecraft.level.entitiesForRendering()) {
      if (entity instanceof EndCrystal crystal
          && crystal.isAlive()
          && !crystal.isRemoved()
          && minecraft.player.distanceToSqr(crystal) <= radiusSquared
          && minecraft.player.hasLineOfSight(crystal)
          && explosionDamage(crystal.position(), crystal) >= health) return true;
    }
    return predictVisiblePlacements.getValue() && lethalVisiblePlacement(health, radiusSquared);
  }

  private boolean lethalVisiblePlacement(double health, double radiusSquared) {
    for (Player player : minecraft.level.players()) {
      if (!validEnemy(player, radiusSquared) || !player.isHolding(Items.END_CRYSTAL)) continue;
      Vec3 eye = player.getEyePosition();
      Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
      BlockHitResult hit =
          minecraft.level.clip(
              new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      if (hit.getType() != HitResult.Type.BLOCK) continue;
      var state = minecraft.level.getBlockState(hit.getBlockPos());
      if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
      BlockPos above = hit.getBlockPos().above();
      if (!minecraft.level.getBlockState(above).isAir()
          || !minecraft.level.getBlockState(above.above()).isAir()) continue;
      if (!minecraft
          .level
          .getEntities(
              (Entity) null,
              new net.minecraft.world.phys.AABB(above).expandTowards(0.0, 1.0, 0.0),
              entity -> entity.isAlive() && !entity.isRemoved())
          .isEmpty()) continue;
      Vec3 explosion = Vec3.atBottomCenterOf(above);
      if (explosion.distanceToSqr(minecraft.player.position()) <= radiusSquared
          && explosionDamage(explosion, null) >= health) return true;
    }
    return false;
  }

  private float explosionDamage(Vec3 position, Entity source) {
    return ExplosionDamage.estimateSelf(minecraft, position, source);
  }

  private boolean nearbyEnemy(double radius) {
    double radiusSquared = radius * radius;
    for (Player player : minecraft.level.players()) {
      if (validEnemy(player, radiusSquared)) return true;
    }
    return false;
  }

  private boolean validEnemy(Player player, double radiusSquared) {
    return player != minecraft.player
        && player.isAlive()
        && !player.isDeadOrDying()
        && !player.isSpectator()
        && !friends.isProtected(player)
        && minecraft.player.distanceToSqr(player) <= radiusSquared;
  }

  private void selectEmergencyTotem() {
    if (!interruptActiveUse.getValue() && minecraft.player.isUsingItem()) return;
    int slot = findHotbarTotem();
    if (slot >= 0) {
      int selected = minecraft.player.getInventory().getSelectedSlot();
      if (selected != slot && emergencyPreviousSlot < 0) emergencyPreviousSlot = selected;
      minecraft.player.getInventory().setSelectedSlot(slot);
    }
  }

  private void restoreAfterEmergency() {
    if (restoreAfterEmergency.getValue()
        && emergencyPreviousSlot >= 0
        && minecraft.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
      minecraft.player.getInventory().setSelectedSlot(emergencyPreviousSlot);
    }
    emergencyPreviousSlot = -1;
  }

  private int findHotbarTotem() {
    Inventory inventory = minecraft.player.getInventory();
    for (int i = 0; i < Inventory.getSelectionSize(); i++) {
      if (inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
    }
    return -1;
  }

  private boolean connected() {
    return minecraft.player != null
        && minecraft.level != null
        && minecraft.gameMode != null
        && minecraft.getConnection() != null
        && minecraft.player.isAlive()
        && !minecraft.player.isDeadOrDying()
        && !minecraft.gameMode.isSpectator();
  }

  private List<Point> layout(InventoryScreen screen, InventoryMenu menu) {
    List<Point> points = new ArrayList<>(menu.slots.size());
    for (Slot slot : menu.slots) points.add(slotPosition(screen, slot));
    return points;
  }

  private Point mousePosition() {
    return new Point(
        minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
        minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
  }

  private Point slotPosition(InventoryScreen screen, Slot slot) {
    AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
    return SlotPathing.slotCenter(accessor.getLeftPos(), accessor.getTopPos(), slot.x, slot.y);
  }

  private void clearSequence() {
    sequence.reset();
    sourceSlot = -1;
    previousSlot = -1;
    releaseControl();
  }

  private void clearInventoryAction() {
    plannedMenuSlot = -1;
    plannedButton = -1;
    inventoryActionAt = 0;
  }

  private void resetInventoryPlan() {
    inventoryOpen = false;
    inventoryPath = null;
    clearInventoryAction();
    inventoryActions.release(this);
  }

  private void releaseControl() {
    combat.release(this);
    inventoryActions.release(this);
  }

  private void reset() {
    sequence.reset();
    tick = 0;
    emergencyUntil = 0;
    sourceSlot = -1;
    previousSlot = -1;
    emergencyPreviousSlot = -1;
    emergencyWasActive = false;
    dimension = null;
    resetInventoryPlan();
    releaseControl();
  }
}
