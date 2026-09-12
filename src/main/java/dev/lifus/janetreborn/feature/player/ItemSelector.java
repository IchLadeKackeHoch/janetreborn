package dev.lifus.janetreborn.feature.player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public record ItemSelector(Type type, String value) {
  public enum Type {
    EXACT_ITEM,
    CATEGORY
  }

  public ItemSelector {
    Objects.requireNonNull(type, "type");
    value = Objects.requireNonNull(value, "value");
    if (value.isBlank()) throw new IllegalArgumentException("Selector value is blank");
    if (type == Type.EXACT_ITEM) {
      Identifier id = Identifier.tryParse(value);
      if (id == null || BuiltInRegistries.ITEM.get(id).isEmpty()) {
        throw new IllegalArgumentException("Unknown item: " + value);
      }
      value = id.toString();
    } else {
      HotbarCategory category = HotbarCategory.valueOf(value.toUpperCase(Locale.ROOT));
      if (!category.selectable()) throw new IllegalArgumentException("Not a selectable category");
      value = category.name();
    }
  }

  public static Optional<ItemSelector> exact(String value) {
    Identifier id = Identifier.tryParse(value);
    if (id == null || BuiltInRegistries.ITEM.get(id).isEmpty()) return Optional.empty();
    return Optional.of(new ItemSelector(Type.EXACT_ITEM, id.toString()));
  }

  public static Optional<ItemSelector> category(String value) {
    try {
      HotbarCategory category = HotbarCategory.valueOf(value.toUpperCase(Locale.ROOT));
      return category.selectable()
          ? Optional.of(new ItemSelector(Type.CATEGORY, category.name()))
          : Optional.empty();
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  public static ItemSelector category(HotbarCategory category) {
    if (!category.selectable()) throw new IllegalArgumentException("Not a selectable category");
    return new ItemSelector(Type.CATEGORY, category.name());
  }

  public boolean matches(ItemStack stack) {
    if (stack.isEmpty()) return false;
    return switch (type) {
      case EXACT_ITEM -> exactItem().map(stack::is).orElse(false);
      case CATEGORY -> category().map(value -> value.matches(stack)).orElse(false);
    };
  }

  public Optional<Item> exactItem() {
    if (type != Type.EXACT_ITEM) return Optional.empty();
    Identifier id = Identifier.tryParse(value);
    return id == null
        ? Optional.empty()
        : BuiltInRegistries.ITEM.get(id).map(holder -> holder.value());
  }

  public Optional<HotbarCategory> category() {
    if (type != Type.CATEGORY) return Optional.empty();
    try {
      HotbarCategory category = HotbarCategory.valueOf(value);
      return category.selectable() ? Optional.of(category) : Optional.empty();
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  public String displayName() {
    if (type == Type.CATEGORY) return category().map(HotbarCategory::getName).orElse(value);
    return exactItem().map(item -> item.getName(new ItemStack(item)).getString()).orElse(value);
  }
}
