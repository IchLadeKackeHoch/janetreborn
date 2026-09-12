package dev.lifus.janetreborn.service.inventory;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class Hotbar {
  private final Minecraft minecraft;
  private int restoreSlot = -1;
  private int temporarySlot = -1;

  public Hotbar(Minecraft minecraft) {
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
  }

  public <T> T withSlot(int slot, Supplier<T> action) {
    Objects.requireNonNull(action, "action");
    selectTemporarily(slot);
    return action.get();
  }

  public void selectTemporarily(int slot) {
    if (minecraft.player == null) throw new IllegalStateException("No local player");
    Inventory inventory = minecraft.player.getInventory();
    int previous = inventory.getSelectedSlot();
    if (slot != previous) {
      if (restoreSlot < 0) restoreSlot = previous;
      temporarySlot = slot;
      inventory.setSelectedSlot(slot);
    }
  }

  public void endTick() {
    if (restoreSlot < 0) return;
    if (minecraft.player != null) {
      Inventory inventory = minecraft.player.getInventory();
      if (inventory.getSelectedSlot() == temporarySlot) inventory.setSelectedSlot(restoreSlot);
    }
    restoreSlot = -1;
    temporarySlot = -1;
  }

  public int find(Item item) {
    Objects.requireNonNull(item, "item");
    return find(stack -> stack.is(item));
  }

  public int find(Predicate<ItemStack> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    if (minecraft.player == null) return -1;
    Inventory inventory = minecraft.player.getInventory();
    for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
      if (predicate.test(inventory.getItem(slot))) return slot;
    }
    return -1;
  }
}
