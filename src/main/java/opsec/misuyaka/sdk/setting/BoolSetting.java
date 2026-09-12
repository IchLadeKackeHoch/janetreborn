package opsec.misuyaka.sdk.setting;

public final class BoolSetting extends Setting<Boolean> {
  public BoolSetting(String name, boolean defaultValue) {
    super(name, defaultValue);
  }

  public BoolSetting(String id, String name, boolean defaultValue) {
    super(id, name, defaultValue);
  }

  public void toggle() {
    setValue(!getValue());
  }
}
