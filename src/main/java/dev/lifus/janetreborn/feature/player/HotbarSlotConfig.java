package dev.lifus.janetreborn.feature.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HotbarSlotConfig {
  private SlotMode mode;
  private final List<ItemSelector> preferences;
  private FallbackPolicy fallback;

  public HotbarSlotConfig(SlotMode mode, List<ItemSelector> preferences, FallbackPolicy fallback) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.preferences = new ArrayList<>(Objects.requireNonNull(preferences, "preferences"));
    if (this.preferences.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Preferences cannot contain null selectors");
    }
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  public static HotbarSlotConfig managed(ItemSelector... preferences) {
    return new HotbarSlotConfig(
        SlotMode.MANAGED, List.of(preferences), FallbackPolicy.BEST_AVAILABLE);
  }

  public static HotbarSlotConfig ignored() {
    return new HotbarSlotConfig(SlotMode.IGNORE, List.of(), FallbackPolicy.BEST_AVAILABLE);
  }

  public static HotbarSlotConfig empty() {
    return new HotbarSlotConfig(SlotMode.EMPTY, List.of(), FallbackPolicy.BEST_AVAILABLE);
  }

  public SlotMode mode() {
    return mode;
  }

  public void setMode(SlotMode mode) {
    this.mode = Objects.requireNonNull(mode, "mode");
  }

  public List<ItemSelector> preferences() {
    return preferences;
  }

  public FallbackPolicy fallback() {
    return fallback;
  }

  public void setFallback(FallbackPolicy fallback) {
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  public HotbarSlotConfig copy() {
    return new HotbarSlotConfig(mode, preferences, fallback);
  }
}
