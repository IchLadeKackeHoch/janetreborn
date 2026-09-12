package dev.lifus.janetreborn.ui;

import dev.lifus.janetreborn.feature.player.FallbackPolicy;
import dev.lifus.janetreborn.feature.player.HotbarCategory;
import dev.lifus.janetreborn.feature.player.HotbarSlotConfig;
import dev.lifus.janetreborn.feature.player.InventoryManagerModule;
import dev.lifus.janetreborn.feature.player.ItemSelector;
import dev.lifus.janetreborn.feature.player.SlotMode;
import imgui.ImGui;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import opsec.misuyaka.sdk.setting.Setting;

final class InventoryManagerView {
  private static final float SLOT_WIDTH = 76;
  private final Consumer<Setting<?>> settingRenderer;
  private final ImString itemSearch = new ImString(128);
  private final ImString categorySearch = new ImString(128);
  private List<ItemChoice> items;
  private int selectedSlot = -1;
  private Picker picker = Picker.NONE;
  private boolean advancedExpanded;

  InventoryManagerView(Consumer<Setting<?>> settingRenderer) {
    this.settingRenderer = settingRenderer;
  }

  void draw(InventoryManagerModule module) {
    section("Basic");
    module.basicSettings().stream().filter(Setting::isVisible).forEach(settingRenderer);

    section("Hotbar Layout");
    ImGui.textDisabled("Managed fills a slot; Ignore never touches it; Empty actively clears it.");
    for (int index = 0; index < 10; index++) {
      if (index > 0) ImGui.sameLine();
      HotbarSlotConfig slot = module.getHotbarSlot(index);
      if (ImGui.button(slotLabel(index, slot) + "##InventorySlot" + index, SLOT_WIDTH, 52)) {
        selectedSlot = index;
        picker = Picker.NONE;
      }
    }
    if (selectedSlot >= 0) drawEditor(module, selectedSlot);

    if (ControlKit.disclosure("InventoryManagerAdvanced", "Advanced settings", advancedExpanded)) {
      advancedExpanded = !advancedExpanded;
    }
    if (advancedExpanded) {
      ImGui.indent(12);
      module.advancedSettings().stream().filter(Setting::isVisible).forEach(settingRenderer);
      ImGui.unindent(12);
    }
  }

  private void drawEditor(InventoryManagerModule module, int index) {
    HotbarSlotConfig slot = module.getHotbarSlot(index);
    ImGui.separator();
    ImGui.text(index == 9 ? "Offhand editor" : "Slot " + (index + 1) + " editor");

    ImGui.text("Slot mode");
    ImGui.sameLine();
    ImGui.setNextItemWidth(180);
    if (ImGui.beginCombo("##InventoryMode" + index, display(slot.mode()))) {
      for (SlotMode mode : SlotMode.values()) {
        boolean selected = slot.mode() == mode;
        if (ImGui.selectable(display(mode), selected)) slot.setMode(mode);
        if (selected) ImGui.setItemDefaultFocus();
      }
      ImGui.endCombo();
    }

    if (slot.mode() == SlotMode.MANAGED) {
      ImGui.text("Ordered preferences");
      if (slot.preferences().isEmpty()) {
        ImGui.textDisabled("No selectors. BEST_AVAILABLE will choose a useful inventory item.");
      }
      drawPreferences(slot, index);
      if (ImGui.button("Add exact item##InventoryExact" + index)) {
        picker = Picker.EXACT;
        itemSearch.clear();
      }
      ImGui.sameLine();
      if (ImGui.button("Add category##InventoryCategory" + index)) {
        picker = Picker.CATEGORY;
        categorySearch.clear();
      }
      ImGui.sameLine();
      if (ImGui.button("Clear preferences##InventoryClear" + index)) {
        slot.preferences().clear();
        picker = Picker.NONE;
      }
      drawFallback(slot, index);
      if (picker == Picker.EXACT) drawExactPicker(slot, index);
      if (picker == Picker.CATEGORY) drawCategoryPicker(slot, index);
    } else if (slot.mode() == SlotMode.IGNORE) {
      ImGui.textDisabled("InventoryManager will never move or discard the item in this slot.");
    } else {
      ImGui.textDisabled("InventoryManager will move this slot's contents back into inventory.");
    }

    if (ImGui.button("Reset slot##InventoryReset" + index)) {
      module.resetHotbarSlot(index);
      picker = Picker.NONE;
    }
    ImGui.sameLine();
    if (ImGui.button("Close editor##InventoryClose" + index)) {
      selectedSlot = -1;
      picker = Picker.NONE;
    }
  }

  private void drawPreferences(HotbarSlotConfig slot, int slotIndex) {
    for (int preference = 0; preference < slot.preferences().size(); preference++) {
      ItemSelector selector = slot.preferences().get(preference);
      ImGui.text((preference + 1) + ". " + selector.displayName());
      ImGui.sameLine();
      if (ImGui.smallButton("Up##InventoryPreference" + slotIndex + "_" + preference)
          && preference > 0) {
        swap(slot.preferences(), preference, preference - 1);
      }
      ImGui.sameLine();
      if (ImGui.smallButton("Down##InventoryPreference" + slotIndex + "_" + preference)
          && preference + 1 < slot.preferences().size()) {
        swap(slot.preferences(), preference, preference + 1);
      }
      ImGui.sameLine();
      if (ImGui.smallButton("Remove##InventoryPreference" + slotIndex + "_" + preference)) {
        slot.preferences().remove(preference);
        preference--;
      }
    }
  }

  private void drawFallback(HotbarSlotConfig slot, int slotIndex) {
    ImGui.setNextItemWidth(220);
    if (!ImGui.beginCombo("Fallback##InventoryFallback" + slotIndex, display(slot.fallback())))
      return;
    for (FallbackPolicy fallback : FallbackPolicy.values()) {
      boolean selected = slot.fallback() == fallback;
      if (ImGui.selectable(display(fallback), selected)) slot.setFallback(fallback);
      if (selected) ImGui.setItemDefaultFocus();
    }
    ImGui.endCombo();
    ImGui.textDisabled(
        slot.fallback() == FallbackPolicy.BEST_AVAILABLE
            ? "Uses the best useful unassigned item when no preference matches."
            : "Leaves the current item in place when no preference matches.");
  }

  private void drawExactPicker(HotbarSlotConfig slot, int slotIndex) {
    ImGui.separator();
    ImGui.text("Choose an exact Minecraft item");
    ImGui.setNextItemWidth(360);
    ImGui.inputTextWithHint(
        "##InventoryItemSearch" + slotIndex, "Search name or minecraft:item_id", itemSearch);
    String query = itemSearch.get().toLowerCase(Locale.ROOT).strip();
    if (ImGui.beginChild("##InventoryItemList" + slotIndex, 0, 220, true)) {
      for (ItemChoice choice : itemChoices()) {
        if (!query.isEmpty() && !choice.searchText().contains(query)) continue;
        if (ImGui.selectable(choice.name() + "  (" + choice.id() + ")##" + choice.id())) {
          ItemSelector.exact(choice.id()).ifPresent(slot.preferences()::add);
          picker = Picker.NONE;
          break;
        }
      }
    }
    ImGui.endChild();
  }

  private void drawCategoryPicker(HotbarSlotConfig slot, int slotIndex) {
    ImGui.separator();
    ImGui.text("Choose a category");
    ImGui.textDisabled("Categories are broad semantic matchers, such as any sword or safe block.");
    ImGui.setNextItemWidth(360);
    ImGui.inputTextWithHint(
        "##InventoryCategorySearch" + slotIndex, "Search categories", categorySearch);
    String query = categorySearch.get().toLowerCase(Locale.ROOT).strip();
    if (ImGui.beginChild("##InventoryCategoryList" + slotIndex, 0, 180, true)) {
      for (HotbarCategory category : HotbarCategory.values()) {
        if (!category.selectable() || !category.getName().contains(query)) continue;
        if (ImGui.selectable(display(category) + "##InventoryCategory" + category)) {
          slot.preferences().add(ItemSelector.category(category));
          picker = Picker.NONE;
          break;
        }
      }
    }
    ImGui.endChild();
  }

  private List<ItemChoice> itemChoices() {
    if (items != null) return items;
    List<ItemChoice> loaded = new ArrayList<>();
    BuiltInRegistries.ITEM.stream()
        .filter(item -> item != Items.AIR)
        .forEach(
            item -> {
              String id = BuiltInRegistries.ITEM.getKey(item).toString();
              String name = item.getName(new ItemStack(item)).getString();
              loaded.add(new ItemChoice(name, id, (name + " " + id).toLowerCase(Locale.ROOT)));
            });
    loaded.sort(Comparator.comparing(ItemChoice::name).thenComparing(ItemChoice::id));
    items = List.copyOf(loaded);
    return items;
  }

  private static String slotLabel(int index, HotbarSlotConfig slot) {
    String name = index == 9 ? "Offhand" : Integer.toString(index + 1);
    if (slot.mode() != SlotMode.MANAGED) return name + "\n" + display(slot.mode());
    String primary =
        slot.preferences().isEmpty()
            ? "Best available"
            : slot.preferences().getFirst().displayName();
    if (primary.length() > 12) primary = primary.substring(0, 11) + "...";
    int additional = Math.max(0, slot.preferences().size() - 1);
    return name + "\nM: " + primary + (additional == 0 ? "" : " +" + additional);
  }

  private static void section(String name) {
    ControlKit.sectionLabel(name);
  }

  private static void swap(List<ItemSelector> values, int left, int right) {
    ItemSelector value = values.get(left);
    values.set(left, values.get(right));
    values.set(right, value);
  }

  private static String display(Enum<?> value) {
    String[] words = value.name().toLowerCase(Locale.ROOT).split("_");
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!result.isEmpty()) result.append(' ');
      result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return result.toString();
  }

  private enum Picker {
    NONE,
    EXACT,
    CATEGORY
  }

  private record ItemChoice(String name, String id, String searchText) {}
}
