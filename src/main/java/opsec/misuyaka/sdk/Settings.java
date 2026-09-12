package opsec.misuyaka.sdk;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ColorSetting;
import opsec.misuyaka.sdk.setting.KeySetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;
import opsec.misuyaka.sdk.setting.Range;
import opsec.misuyaka.sdk.setting.RangeSetting;
import opsec.misuyaka.sdk.setting.Setting;

public final class Settings {
  private final List<Setting<?>> values = new ArrayList<>();
  private final Set<String> ids = new HashSet<>();

  public <S extends Setting<?>> S add(S setting) {
    Objects.requireNonNull(setting, "setting");
    if (!ids.add(setting.getId())) {
      throw new IllegalArgumentException("Duplicate setting id: " + setting.getId());
    }
    values.add(setting);
    return setting;
  }

  public BoolSetting bool(String name, boolean defaultValue) {
    return add(new BoolSetting(name, defaultValue));
  }

  public <N extends Number & Comparable<N>> NumberSetting<N> number(
      String name, N defaultValue, N min, N max, N step) {
    return add(new NumberSetting<>(name, defaultValue, min, max, step));
  }

  public <N extends Number & Comparable<N>> RangeSetting<N> range(
      String name, Range<N> defaultValue, N min, N max, N step) {
    return add(new RangeSetting<>(name, defaultValue, min, max, step));
  }

  public <E extends Enum<E>> ModeSetting<E> mode(String name, E defaultValue) {
    return add(new ModeSetting<>(name, defaultValue));
  }

  public ColorSetting color(String name, Color defaultValue) {
    return add(new ColorSetting(name, defaultValue));
  }

  public KeySetting key(String name) {
    return add(new KeySetting(name));
  }

  public List<Setting<?>> all() {
    return List.copyOf(values);
  }
}
