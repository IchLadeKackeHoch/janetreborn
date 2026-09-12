package dev.lifus.janetreborn.feature.player;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import dev.lifus.janetreborn.platform.minecraft.mixin.AbstractContainerScreenAccessor;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.service.inventory.InventoryGuard;
import dev.lifus.janetreborn.service.inventory.SlotPathing;
import dev.lifus.janetreborn.service.inventory.SlotPathing.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;

public final class ChestStealerModule extends Module {
  private static final int REQUIRED_STATIONARY_TICKS = 2;
  private static final int MIN_ACTION_INTERVAL_TICKS = 2;
  private static final int MIN_EMPTY_TICKS_BEFORE_CLOSE = 3;
  static final int DEFAULT_MIN_START_DELAY = 250;
  static final int DEFAULT_MAX_START_DELAY = 400;
  static final int DEFAULT_MIN_ACTION_DELAY = 60;
  static final int DEFAULT_MAX_ACTION_DELAY = 125;
  static final int DEFAULT_MISS_CHANCE = 4;
  static final int DEFAULT_MIN_CLOSE_DELAY = 180;
  static final int DEFAULT_MAX_CLOSE_DELAY = 300;
  private final Minecraft minecraft;
  private final InventoryActionGate inventoryActions;
  private final BoolSetting noMove = add(new BoolSetting("no_move", "Pause While Moving", true));
  private final BoolSetting disableOnTeleport = add(new BoolSetting("Disable On Teleport", true));
  private final ModeSetting<ItemPolicy> itemPolicy =
      add(new ModeSetting<>("Item Policy", ItemPolicy.ALL));
  private final RangeSetting<Integer> startDelay =
      add(
          new RangeSetting<>(
                  "Start Delay",
                  new Range<>(DEFAULT_MIN_START_DELAY, DEFAULT_MAX_START_DELAY),
                  0,
                  1000,
                  1)
              .legacyBounds("minimum_start_delay", "maximum_start_delay")
              .unit("ms"));
  private final RangeSetting<Integer> actionDelay =
      add(
          new RangeSetting<>(
                  "Action Delay",
                  new Range<>(DEFAULT_MIN_ACTION_DELAY, DEFAULT_MAX_ACTION_DELAY),
                  0,
                  250,
                  1)
              .legacyBounds("minimum_delay", "maximum_delay")
              .unit("ms"));
  private final opsec.misuyaka.sdk.setting.NumberSetting<Integer> missChance =
      add(
          new opsec.misuyaka.sdk.setting.NumberSetting<>(
                  "miss_chance", "Skip Chance", DEFAULT_MISS_CHANCE, 0, 100, 1)
              .unit("%"));
  private final BoolSetting autoClose = add(new BoolSetting("Auto Close", true));
  private final RangeSetting<Integer> closeDelay =
      add(
          new RangeSetting<>(
                  "Close Delay",
                  new Range<>(DEFAULT_MIN_CLOSE_DELAY, DEFAULT_MAX_CLOSE_DELAY),
                  0,
                  500,
                  1)
              .legacyBounds("minimum_close_delay", "maximum_close_delay")
              .unit("ms"));
  private int containerId = -1;
  private int stationaryTicks;
  private int ticksSinceAction;
  private int emptyTicks;
  private int plannedSlot = -1;
  private Point pathPosition;
  private long nextStealAt;
  private long closeAt;

  @Subscribe private final Listener<TickEndEvent> tickEnd = this::tickEnd;

  @Subscribe
  private final Listener<ServerTeleportEvent> serverTeleport =
      ignored -> {
        if (disableOnTeleport.getValue()) setEnabled(false);
      };

  public ChestStealerModule(Minecraft minecraft, InventoryActionGate inventoryActions) {
    super("ChestStealer", "Takes nearby container items", Category.PLAYER);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.inventoryActions = Objects.requireNonNull(inventoryActions, "inventoryActions");
    closeDelay.visibleWhen(autoClose);
  }

  @Override
  protected void onDisable() {
    reset();
  }

  private void tickEnd(TickEndEvent event) {
    if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
        || minecraft.player == null
        || minecraft.gameMode == null
        || screen.getMenu().containerId == 0
        || minecraft.player.containerMenu != screen.getMenu()) {
      reset();
      return;
    }
    long now = System.nanoTime();
    if (containerId != screen.getMenu().containerId) {
      containerId = screen.getMenu().containerId;
      stationaryTicks = 0;
      ticksSinceAction = 0;
      emptyTicks = 0;
      plannedSlot = -1;
      pathPosition = mousePosition();
      nextStealAt = now + delay(startDelay) * 1_000_000L;
      return;
    }
    ticksSinceAction = Math.min(MIN_ACTION_INTERVAL_TICKS, ticksSinceAction + 1);
    if (!InventoryGuard.mayClick(minecraft.player, noMove.getValue())
        || !screen.getMenu().getCarried().isEmpty()) {
      stationaryTicks = 0;
      return;
    }
    stationaryTicks = Math.min(REQUIRED_STATIONARY_TICKS, stationaryTicks + 1);
    if (stationaryTicks < REQUIRED_STATIONARY_TICKS) return;
    if (plannedSlot < 0 && now < nextStealAt) return;
    int slot = nearestSlot(screen, pathPosition);
    if (slot < 0) {
      plannedSlot = -1;
      emptyTicks = Math.min(MIN_EMPTY_TICKS_BEFORE_CLOSE, emptyTicks + 1);
      if (!autoClose.getValue()) {
        closeAt = 0;
        return;
      }
      if (closeAt == 0) closeAt = now + delay(closeDelay) * 1_000_000L;
      else if (now >= closeAt && mayClose(emptyTicks)) {
        screen.onClose();
        reset();
      }
      return;
    }
    emptyTicks = 0;
    closeAt = 0;
    if (plannedSlot != slot) {
      plannedSlot = slot;
      int travelDelay = travelDelay(screen, pathPosition, slot);
      nextStealAt = Math.max(nextStealAt, now + travelDelay * 1_000_000L);
    }
    if (now < nextStealAt) return;
    if (!mayAct(ticksSinceAction)) return;
    if (!miss()) {
      if (!inventoryActions.acquire(this, 10, 1)) return;
      if (!inventoryActions.markAction(this)) {
        inventoryActions.release(this);
        return;
      }
      minecraft.gameMode.handleContainerInput(
          containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
      inventoryActions.release(this);
      ticksSinceAction = 0;
    }
    pathPosition = slotPosition(screen, slot);
    plannedSlot = -1;
    nextStealAt = 0;
  }

  static boolean mayAct(int ticksSinceAction) {
    return ticksSinceAction >= MIN_ACTION_INTERVAL_TICKS;
  }

  static boolean mayClose(int emptyTicks) {
    return emptyTicks >= MIN_EMPTY_TICKS_BEFORE_CLOSE;
  }

  private boolean miss() {
    return ThreadLocalRandom.current().nextInt(100) < missChance.getValue();
  }

  private int nearestSlot(AbstractContainerScreen<?> screen, Point origin) {
    List<Point> candidates = new ArrayList<>(screen.getMenu().slots.size());
    for (int i = 0; i < screen.getMenu().slots.size(); i++) {
      Slot slot = screen.getMenu().slots.get(i);
      if (slot.container == minecraft.player.getInventory()
          || !slot.hasItem()
          || itemPolicy.getValue() == ItemPolicy.USEFUL_ONLY && TrashItems.contains(slot.getItem())
          || !slot.mayPickup(minecraft.player)) candidates.add(null);
      else candidates.add(slotPosition(screen, i));
    }
    return SlotPathing.nearest(origin, candidates);
  }

  private int travelDelay(AbstractContainerScreen<?> screen, Point from, int destination) {
    List<Point> layout = new ArrayList<>(screen.getMenu().slots.size());
    for (Slot slot : screen.getMenu().slots) layout.add(slotPosition(screen, slot));
    return SlotPathing.travelDelayMillis(
        from,
        slotPosition(screen, destination),
        layout,
        actionDelay.getValue().minimum(),
        actionDelay.getValue().maximum());
  }

  private Point mousePosition() {
    return new Point(
        minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
        minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
  }

  private Point slotPosition(AbstractContainerScreen<?> screen, int menuSlot) {
    return slotPosition(screen, screen.getMenu().getSlot(menuSlot));
  }

  private Point slotPosition(AbstractContainerScreen<?> screen, Slot slot) {
    AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
    return SlotPathing.slotCenter(accessor.getLeftPos(), accessor.getTopPos(), slot.x, slot.y);
  }

  private int delay(RangeSetting<Integer> setting) {
    int min = setting.getValue().minimum();
    int max = setting.getValue().maximum();
    return ThreadLocalRandom.current().nextInt(min, max + 1);
  }

  private enum ItemPolicy {
    ALL,
    USEFUL_ONLY
  }

  private void reset() {
    inventoryActions.release(this);
    containerId = -1;
    stationaryTicks = 0;
    ticksSinceAction = 0;
    emptyTicks = 0;
    plannedSlot = -1;
    pathPosition = null;
    nextStealAt = 0;
    closeAt = 0;
  }
}
