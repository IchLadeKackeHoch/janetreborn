package opsec.misuyaka.sdk.setting;

import java.awt.Color;

public final class ColorSetting extends Setting<Color> {
  public ColorSetting(String name, Color defaultValue) {
    super(name, defaultValue);
  }

  public ColorSetting(String id, String name, Color defaultValue) {
    super(id, name, defaultValue);
  }
}
