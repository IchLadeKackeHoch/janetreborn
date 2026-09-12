package dev.lifus.janetreborn.feature.player;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.config.ConfigNode;
import dev.lifus.janetreborn.event.game.ServerTeleportEvent;
import dev.lifus.janetreborn.event.game.TickEndEvent;
import dev.lifus.janetreborn.platform.minecraft.mixin.AbstractContainerScreenAccessor;
import dev.lifus.janetreborn.service.inventory.InventoryActionGate;
import dev.lifus.janetreborn.service.inventory.InventoryGuard;
import dev.lifus.janetreborn.service.inventory.ItemScore;
import dev.lifus.janetreborn.service.inventory.SlotPathing;
import dev.lifus.janetreborn.service.inventory.SlotPathing.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;
import opsec.misuyaka.sdk.setting.Setting;

public final class InventoryManagerModule extends Module {
  private static final EquipmentSlot[] ARMOR = {
    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
  };
  private static final Set<HotbarCategory> UNIQUE =
      EnumSet.of(
          HotbarCategory.SWORD,
          HotbarCategory.SPEAR,
          HotbarCategory.MACE,
          HotbarCategory.BOW,
          HotbarCategory.CROSSBOW,
          HotbarCategory.AXE,
          HotbarCategory.PICKAXE,
          HotbarCategory.SHOVEL,
          HotbarCategory.HOE,
          HotbarCategory.ROD,
          HotbarCategory.SHIELD);
  private final Minecraft minecraft;
  private final InventoryActionGate inventoryActions;
  private final BoolSetting autoArmor = add(new BoolSetting("Auto Armor", true));
  private final BoolSetting sortHotbar = add(new BoolSetting("Sort Hotbar", true));
  private final BoolSetting dropTrash = add(new BoolSetting("Drop Trash", true));
  private final BoolSetting noMove = add(new BoolSetting("no_move", "Pause While Moving", true));
  private final BoolSetting disableOnTeleport = add(new BoolSetting("Disable On Teleport", true));
  private final NumberSetting<Integer> arrowLimit =
      add(new NumberSetting<>("Arrow Limit", 128, 0, 2304, 64));
  private final RangeSetting<Integer> startDelay =
      add(
          new RangeSetting<>("Start Delay", new Range<>(300, 450), 0, 1000, 1)
              .legacyBounds("minimum_start_delay", "maximum_start_delay")
              .unit("ms"));
  private final RangeSetting<Integer> actionDelay =
      add(
          new RangeSetting<>("Action Delay", new Range<>(35, 90), 0, 250, 1)
              .legacyBounds("minimum_delay", "maximum_delay")
              .unit("ms"));
  private final NumberSetting<Integer> missChance =
      add(new NumberSetting<>("miss_chance", "Pause Chance", 8, 0, 100, 1).unit("%"));
  private final List<HotbarSlotConfig> hotbarSlots = defaultHotbarSlots();
  private final HotbarPlanner hotbarPlanner = new HotbarPlanner();
  private boolean inventoryOpen;
  private int plannedSlot = -1;
  private int plannedButton;
  private ContainerInput plannedInput;
  private Point pathPosition;
  private long nextActionAt;
  private ArmorSwap armorSwap;

  @Subscribe private final Listener<TickEndEvent> tickEnd = this::tickEnd;

  @Subscribe
  private final Listener<ServerTeleportEvent> serverTeleport =
      ignored -> {
        if (disableOnTeleport.getValue()) setEnabled(false);
      };

  public InventoryManagerModule(Minecraft minecraft, InventoryActionGate inventoryActions) {
    super("InventoryManager", "Manages the open inventory", Category.PLAYER);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.inventoryActions = Objects.requireNonNull(inventoryActions, "inventoryActions");
    arrowLimit.visibleWhen(dropTrash);
  }

  public HotbarSlotConfig getHotbarSlot(int slot) {
    return hotbarSlots.get(slot);
  }

  public void resetHotbarSlot(int slot) {
    hotbarSlots.set(slot, defaultHotbarSlots().get(slot));
  }

  public List<Setting<?>> basicSettings() {
    return List.of(autoArmor, sortHotbar, dropTrash);
  }

  public List<Setting<?>> advancedSettings() {
    return List.of(noMove, disableOnTeleport, arrowLimit, startDelay, actionDelay, missChance);
  }

  @Override
  public void writeConfig(ConfigNode config) {
    HotbarLayoutCodec.write(config, hotbarSlots);
  }

  @Override
  public void readConfig(ConfigNode config) {
    HotbarLayoutCodec.Result decoded = HotbarLayoutCodec.read(config, defaultHotbarSlots());
    hotbarSlots.clear();
    hotbarSlots.addAll(decoded.slots());
  }

  @Override
  protected void onDisable() {
    resetDelay();
  }

  private void tickEnd(TickEndEvent event) {
    if (!(minecraft.gui.screen() instanceof InventoryScreen)
        || minecraft.player == null
        || minecraft.gameMode == null
        || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
      resetDelay();
      return;
    }
    long now = System.nanoTime();
    if (!inventoryOpen) {
      inventoryOpen = true;
      pathPosition = mousePosition();
      nextActionAt = now + delay(startDelay) * 1_000_000L;
      return;
    }
    if (!InventoryGuard.mayClick(minecraft.player, noMove.getValue())) return;
    if (now < nextActionAt) return;
    InventoryMenu menu = minecraft.player.inventoryMenu;
    runNextAction(menu);
  }

  private void runNextAction(InventoryMenu menu) {
    if (armorSwap != null) {
      continueArmorSwap(menu);
      return;
    }
    if (!menu.getCarried().isEmpty()) return;
    if (autoArmor.getValue() && equipArmor(menu)) return;
    if (sortHotbar.getValue() && sortHotbar(menu)) return;
    if (dropTrash.getValue() && enforceArrows(menu)) return;
    if (dropTrash.getValue()) discardTrash(menu);
  }

  private boolean equipArmor(InventoryMenu menu) {
    int selectedSource = -1;
    int selectedDestination = -1;
    int selectedClick = -1;
    for (EquipmentSlot equipment : ARMOR) {
      int destination = armorMenuSlot(equipment);
      int best = bestArmor(menu, equipment);
      if (best < 0 || best == destination) continue;
      ItemStack equipped = menu.getSlot(destination).getItem();
      ItemStack candidate = menu.getSlot(best).getItem();
      if (!equipped.isEmpty()
          && ArmorScorer.score(candidate, equipment) <= ArmorScorer.score(equipped, equipment))
        continue;
      if (!equipped.isEmpty()
          && (ArmorScorer.hasBindingCurse(equipped)
              || !menu.getSlot(destination).mayPickup(minecraft.player))) continue;

      int sourceInventorySlot = inventoryIndex(menu.getSlot(best));
      int firstClick =
          equipped.isEmpty()
                  || !Inventory.isHotbarSlot(sourceInventorySlot)
                      && minecraft.player.getInventory().getFreeSlot() < 0
              ? best
              : destination;
      if (!nearer(menu, firstClick, selectedClick)) continue;
      selectedSource = best;
      selectedDestination = destination;
      selectedClick = firstClick;
    }
    if (selectedSource < 0) return false;

    ItemStack equipped = menu.getSlot(selectedDestination).getItem();
    ItemStack candidate = menu.getSlot(selectedSource).getItem();
    if (equipped.isEmpty()) {
      click(menu, selectedSource, 0, ContainerInput.QUICK_MOVE);
    } else {
      int sourceInventorySlot = inventoryIndex(menu.getSlot(selectedSource));
      if (Inventory.isHotbarSlot(sourceInventorySlot)) {
        click(menu, selectedDestination, sourceInventorySlot, ContainerInput.SWAP);
      } else if (minecraft.player.getInventory().getFreeSlot() >= 0) {
        click(menu, selectedDestination, 0, ContainerInput.QUICK_MOVE);
      } else {
        beginArmorSwap(menu, selectedSource, selectedDestination, candidate, equipped);
      }
    }
    return true;
  }

  private void beginArmorSwap(
      InventoryMenu menu, int source, int destination, ItemStack candidate, ItemStack equipped) {
    int hotbarButton = temporaryHotbarSlot();
    int hotbarMenuSlot = menuSlot(menu, hotbarButton);
    if (hotbarMenuSlot < 0) return;
    ArmorSwap pending =
        new ArmorSwap(
            source,
            destination,
            hotbarButton,
            candidate.copy(),
            equipped.copy(),
            menu.getSlot(hotbarMenuSlot).getItem().copy(),
            ArmorSwap.HOTBAR_READY);
    if (click(menu, source, hotbarButton, ContainerInput.SWAP)) armorSwap = pending;
  }

  private boolean continueArmorSwap(InventoryMenu menu) {
    ArmorSwap pending = armorSwap;
    int hotbarMenuSlot = menuSlot(menu, pending.hotbarButton);
    if (hotbarMenuSlot < 0 || !menu.getCarried().isEmpty()) {
      armorSwap = null;
      return false;
    }

    if (pending.phase == ArmorSwap.HOTBAR_READY) {
      if (!ItemStack.matches(menu.getSlot(hotbarMenuSlot).getItem(), pending.candidate)
          || !ItemStack.matches(menu.getSlot(pending.source).getItem(), pending.hotbarItem)
          || !ItemStack.matches(menu.getSlot(pending.destination).getItem(), pending.equipped)) {
        armorSwap = null;
        return false;
      }
      if (click(menu, pending.destination, pending.hotbarButton, ContainerInput.SWAP))
        pending.phase = ArmorSwap.RESTORE_HOTBAR;
      return true;
    }

    if (!ItemStack.matches(menu.getSlot(hotbarMenuSlot).getItem(), pending.equipped)
        || !ItemStack.matches(menu.getSlot(pending.source).getItem(), pending.hotbarItem)
        || !ItemStack.matches(menu.getSlot(pending.destination).getItem(), pending.candidate)) {
      armorSwap = null;
      return false;
    }
    if (click(menu, pending.source, pending.hotbarButton, ContainerInput.SWAP)) armorSwap = null;
    return true;
  }

  private boolean sortHotbar(InventoryMenu menu) {
    HotbarPlanner.Plan plan = hotbarPlan(menu);
    int selectedTarget = -1;
    int selectedSource = -1;
    int selectedClick = -1;
    for (int target = 0; target < hotbarSlots.size(); target++) {
      if (hotbarSlots.get(target).mode() != SlotMode.EMPTY) continue;
      int destination = menuSlot(menu, target == 9 ? Inventory.SLOT_OFFHAND : target);
      if (destination < 0) continue;
      if (!menu.getSlot(destination).getItem().isEmpty()
          && nearer(menu, destination, selectedClick)) {
        selectedTarget = target;
        selectedSource = -1;
        selectedClick = destination;
      }
    }
    if (selectedTarget >= 0) {
      click(menu, selectedClick, 0, ContainerInput.QUICK_MOVE);
      return true;
    }

    for (HotbarPlanner.Assignment assignment : plan.assignments()) {
      if (assignment.mode() != SlotMode.MANAGED || assignment.candidate() == null) continue;
      int target = assignment.target();
      int destination = menuSlot(menu, target == 9 ? Inventory.SLOT_OFFHAND : target);
      int source = assignment.candidate().sourceSlot();
      if (destination < 0 || source == destination) continue;
      if (nearer(menu, source, selectedClick)) {
        selectedTarget = target;
        selectedSource = source;
        selectedClick = source;
      }
    }
    if (selectedTarget < 0) return false;
    click(
        menu,
        selectedSource,
        selectedTarget == 9 ? Inventory.SLOT_OFFHAND : selectedTarget,
        ContainerInput.SWAP);
    return true;
  }

  private boolean enforceArrows(InventoryMenu menu) {
    Set<Integer> protectedSlots = protectedSlots(menu);
    int total = 0;
    for (int i = 0; i < menu.slots.size(); i++)
      if (inventorySlot(menu.getSlot(i)) && menu.getSlot(i).getItem().is(ItemTags.ARROWS))
        total += menu.getSlot(i).getItem().getCount();
    if (total <= arrowLimit.getValue()) return false;
    int excess = total - arrowLimit.getValue();
    int selected = -1;
    for (int i = 0; i < menu.slots.size(); i++) {
      Slot slot = menu.getSlot(i);
      if (!inventorySlot(slot) || !slot.getItem().is(ItemTags.ARROWS)) continue;
      if (protectedSlots.contains(i)) continue;
      if (nearer(menu, i, selected)) selected = i;
    }
    if (selected < 0) return false;
    click(
        menu,
        selected,
        menu.getSlot(selected).getItem().getCount() <= excess ? 1 : 0,
        ContainerInput.THROW);
    return true;
  }

  private boolean discardTrash(InventoryMenu menu) {
    Set<Integer> protectedSlots = protectedSlots(menu);
    int selected = -1;
    for (int i = 0; i < menu.slots.size(); i++) {
      if (discardable(menu, i, protectedSlots) && nearer(menu, i, selected)) selected = i;
    }
    if (selected < 0) return false;
    click(menu, selected, 1, ContainerInput.THROW);
    return true;
  }

  private boolean discardable(InventoryMenu menu, int menuSlot, Set<Integer> protectedSlots) {
    Slot slot = menu.getSlot(menuSlot);
    ItemStack stack = slot.getItem();
    if (!inventorySlot(slot) || stack.isEmpty()) return false;
    if (protectedSlots.contains(menuSlot)) return false;
    if (TrashItems.contains(stack)) return true;

    Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
    if (equippable != null && equippable.slot().isArmor()) {
      if (menuSlot == armorMenuSlot(equippable.slot())) return false;
      int best = bestArmor(menu, equippable.slot());
      if (best >= 0
          && shouldDiscardArmor(
              menuSlot,
              best,
              ArmorScorer.score(stack, equippable.slot()),
              ArmorScorer.score(menu.getSlot(best).getItem(), equippable.slot()))) return true;
    }

    HotbarCategory category = uniqueCategory(stack);
    if (category == null) return false;
    int best = bestForCategory(menu, category, protectedSlots);
    return best >= 0
        && shouldDiscardEquipment(
            menuSlot,
            best,
            ItemScore.score(stack, EquipmentSlot.MAINHAND),
            ItemScore.score(menu.getSlot(best).getItem(), EquipmentSlot.MAINHAND));
  }

  private int bestArmor(InventoryMenu menu, EquipmentSlot equipment) {
    int best = -1;
    double score = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < menu.slots.size(); i++) {
      Slot slot = menu.getSlot(i);
      if (!inventorySlot(slot) && i != armorMenuSlot(equipment)) continue;
      ItemStack stack = slot.getItem();
      Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
      if (stack.isEmpty() || equippable == null || equippable.slot() != equipment) continue;
      double current = ArmorScorer.score(stack, equipment);
      if (current > score) {
        score = current;
        best = i;
      }
    }
    return best;
  }

  private int bestForCategory(
      InventoryMenu menu, HotbarCategory category, Set<Integer> protectedSlots) {
    int best = -1;
    double score = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < menu.slots.size(); i++) {
      Slot slot = menu.getSlot(i);
      if (!inventorySlot(slot) || !category.matches(slot.getItem())) continue;
      double current = score(category, slot.getItem());
      if (current > score
          || current == score
              && protectedSlots.contains(i)
              && (best < 0 || !protectedSlots.contains(best))) {
        score = current;
        best = i;
      }
    }
    return best;
  }

  private HotbarCategory uniqueCategory(ItemStack stack) {
    for (HotbarCategory category : UNIQUE) if (category.matches(stack)) return category;
    return null;
  }

  private double score(HotbarCategory category, ItemStack stack) {
    return category == HotbarCategory.FOOD
        ? ItemScore.foodScore(stack)
        : ItemScore.score(stack, EquipmentSlot.MAINHAND);
  }

  private Set<Integer> protectedSlots(InventoryMenu menu) {
    Set<Integer> protectedSlots = new HashSet<>(hotbarPlan(menu).reservedSourceSlots());
    for (int target = 0; target < hotbarSlots.size(); target++) {
      if (hotbarSlots.get(target).mode() != SlotMode.IGNORE) continue;
      int menuSlot = menuSlot(menu, target == 9 ? Inventory.SLOT_OFFHAND : target);
      if (menuSlot >= 0) protectedSlots.add(menuSlot);
    }
    return protectedSlots;
  }

  static boolean protectsFromQualityDrop(HotbarCategory category) {
    return category != HotbarCategory.IGNORE && category != HotbarCategory.NONE;
  }

  static boolean shouldDiscardArmor(
      int candidateSlot, int bestSlot, double candidate, double best) {
    return candidateSlot != bestSlot && candidate <= best;
  }

  static boolean shouldDiscardEquipment(
      int candidateSlot, int bestSlot, double candidate, double best) {
    return candidateSlot != bestSlot && candidate <= best;
  }

  private HotbarPlanner.Plan hotbarPlan(InventoryMenu menu) {
    List<HotbarPlanner.Candidate> candidates = new ArrayList<>();
    for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
      Slot slot = menu.getSlot(menuSlot);
      int inventorySlot = inventoryIndex(slot);
      if (!plannerInventorySlot(inventorySlot) || slot.getItem().isEmpty()) continue;
      int currentTarget = targetForInventorySlot(inventorySlot);
      if (currentTarget >= 0 && hotbarSlots.get(currentTarget).mode() != SlotMode.MANAGED) continue;
      candidates.add(
          new HotbarPlanner.Candidate(
              menuSlot, currentTarget, slot.getItem(), !TrashItems.contains(slot.getItem())));
    }
    return hotbarPlanner.plan(hotbarSlots, candidates);
  }

  private static boolean plannerInventorySlot(int slot) {
    return slot >= 0 && (slot < Inventory.INVENTORY_SIZE || slot == Inventory.SLOT_OFFHAND);
  }

  private static int targetForInventorySlot(int slot) {
    if (Inventory.isHotbarSlot(slot)) return slot;
    return slot == Inventory.SLOT_OFFHAND ? 9 : -1;
  }

  private boolean inventorySlot(Slot slot) {
    return slot.container == minecraft.player.getInventory()
        && slot.getContainerSlot() >= 0
        && slot.getContainerSlot() <= Inventory.SLOT_OFFHAND;
  }

  private int menuSlot(InventoryMenu menu, int inventorySlot) {
    for (int i = 0; i < menu.slots.size(); i++) {
      Slot slot = menu.getSlot(i);
      if (slot.container == minecraft.player.getInventory()
          && slot.getContainerSlot() == inventorySlot) return i;
    }
    return -1;
  }

  private int inventoryIndex(Slot slot) {
    return slot.container == minecraft.player.getInventory() ? slot.getContainerSlot() : -1;
  }

  private int temporaryHotbarSlot() {
    for (int i = 0; i < 9; i++) if (hotbarSlots.get(i).mode() == SlotMode.IGNORE) return i;
    return 7;
  }

  private int armorMenuSlot(EquipmentSlot slot) {
    return switch (slot) {
      case HEAD -> InventoryMenu.ARMOR_SLOT_START;
      case CHEST -> InventoryMenu.ARMOR_SLOT_START + 1;
      case LEGS -> InventoryMenu.ARMOR_SLOT_START + 2;
      case FEET -> InventoryMenu.ARMOR_SLOT_START + 3;
      default -> -1;
    };
  }

  private boolean nearer(InventoryMenu menu, int candidate, int current) {
    if (current < 0) return true;
    if (!(minecraft.gui.screen() instanceof InventoryScreen screen)) return candidate < current;
    Point candidatePosition = slotPosition(screen, menu.getSlot(candidate));
    Point currentPosition = slotPosition(screen, menu.getSlot(current));
    return pathPosition.distanceSquared(candidatePosition)
        < pathPosition.distanceSquared(currentPosition);
  }

  private boolean click(InventoryMenu menu, int slot, int button, ContainerInput input) {
    if (!(minecraft.gui.screen() instanceof InventoryScreen screen)) return false;
    long now = System.nanoTime();
    if (plannedSlot != slot || plannedButton != button || plannedInput != input) {
      plannedSlot = slot;
      plannedButton = button;
      plannedInput = input;
      int travelDelay = travelDelay(screen, menu, pathPosition, slot);
      nextActionAt = now + travelDelay * 1_000_000L;
    }
    if (now < nextActionAt) return false;

    pathPosition = slotPosition(screen, menu.getSlot(slot));
    if (miss()) {
      clearPlannedClick();
      return false;
    }
    if (!inventoryActions.acquire(this, 20, 1)) return false;
    if (!inventoryActions.markAction(this)) {
      inventoryActions.release(this);
      return false;
    }
    minecraft.gameMode.handleContainerInput(
        menu.containerId, slot, button, input, minecraft.player);
    inventoryActions.release(this);
    clearPlannedClick();
    return true;
  }

  private void clearPlannedClick() {
    plannedSlot = -1;
    plannedInput = null;
    nextActionAt = 0;
  }

  private int travelDelay(InventoryScreen screen, InventoryMenu menu, Point from, int destination) {
    List<Point> layout = new ArrayList<>(menu.slots.size());
    for (Slot slot : menu.slots) layout.add(slotPosition(screen, slot));
    return SlotPathing.travelDelayMillis(
        from,
        slotPosition(screen, menu.getSlot(destination)),
        layout,
        actionDelay.getValue().minimum(),
        actionDelay.getValue().maximum());
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

  private boolean miss() {
    return ThreadLocalRandom.current().nextInt(100) < missChance.getValue();
  }

  private int delay(RangeSetting<Integer> setting) {
    int min = setting.getValue().minimum();
    int max = setting.getValue().maximum();
    return ThreadLocalRandom.current().nextInt(min, max + 1);
  }

  private void resetDelay() {
    inventoryActions.release(this);
    inventoryOpen = false;
    plannedSlot = -1;
    plannedInput = null;
    pathPosition = null;
    nextActionAt = 0;
    armorSwap = null;
  }

  private static final class ArmorSwap {
    private static final int HOTBAR_READY = 1;
    private static final int RESTORE_HOTBAR = 2;

    private final int source;
    private final int destination;
    private final int hotbarButton;
    private final ItemStack candidate;
    private final ItemStack equipped;
    private final ItemStack hotbarItem;
    private int phase;

    private ArmorSwap(
        int source,
        int destination,
        int hotbarButton,
        ItemStack candidate,
        ItemStack equipped,
        ItemStack hotbarItem,
        int phase) {
      this.source = source;
      this.destination = destination;
      this.hotbarButton = hotbarButton;
      this.candidate = candidate;
      this.equipped = equipped;
      this.hotbarItem = hotbarItem;
      this.phase = phase;
    }
  }

  private static List<HotbarSlotConfig> defaultHotbarSlots() {
    List<HotbarSlotConfig> slots = new ArrayList<>(HotbarLayoutCodec.SLOT_COUNT);
    for (int index = 0; index < HotbarLayoutCodec.SLOT_COUNT; index++) {
      slots.add(HotbarSlotConfig.ignored());
    }
    slots.set(0, HotbarSlotConfig.managed(ItemSelector.category(HotbarCategory.SWORD)));
    slots.set(1, HotbarSlotConfig.managed(ItemSelector.category(HotbarCategory.AXE)));
    slots.set(8, HotbarSlotConfig.managed(ItemSelector.category(HotbarCategory.BLOCK)));
    slots.set(9, HotbarSlotConfig.managed(ItemSelector.category(HotbarCategory.SHIELD)));
    return slots;
  }
}
