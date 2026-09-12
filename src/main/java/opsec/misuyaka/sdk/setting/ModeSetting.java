package opsec.misuyaka.sdk.setting;

public final class ModeSetting<E extends Enum<E>> extends Setting<E> {
  private final E[] values;

  public ModeSetting(String name, E defaultValue) {
    super(name, defaultValue);
    values = defaultValue.getDeclaringClass().getEnumConstants();
  }

  public ModeSetting(String id, String name, E defaultValue) {
    super(id, name, defaultValue);
    values = defaultValue.getDeclaringClass().getEnumConstants();
  }

  public void next() {
    setValue(values[(getValue().ordinal() + 1) % values.length]);
  }

  public void setIndex(int index) {
    if (index < 0 || index >= values.length) {
      throw new IllegalArgumentException("Mode index is out of range: " + index);
    }
    setValue(values[index]);
  }

  public E[] getValues() {
    return values.clone();
  }
}
