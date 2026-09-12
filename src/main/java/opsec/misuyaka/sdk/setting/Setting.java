package opsec.misuyaka.sdk.setting;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import lombok.Getter;

public abstract class Setting<T> {
  private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
  @Getter private final String id;
  @Getter private final String name;
  @Getter private final T defaultValue;
  @Getter private T value;
  @Getter private String description = "";
  @Getter private String unit = "";
  @Getter private boolean advanced;
  private BooleanSupplier visibility = () -> true;

  protected Setting(String name, T defaultValue) {
    this(defaultId(name), name, defaultValue);
  }

  protected Setting(String id, String name, T defaultValue) {
    this.id = requireId(id);
    this.name = Objects.requireNonNull(name, "name").trim();
    if (this.name.isBlank()) throw new IllegalArgumentException("Setting name cannot be blank");
    this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
    value = defaultValue;
  }

  private static String defaultId(String name) {
    String id =
        Objects.requireNonNull(name, "name")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_");
    return requireId(id.replaceAll("^_+|_+$", ""));
  }

  private static String requireId(String value) {
    String id = Objects.requireNonNull(value, "id").trim().toLowerCase(Locale.ROOT);
    if (!VALID_ID.matcher(id).matches())
      throw new IllegalArgumentException("Invalid setting id: " + value);
    return id;
  }

  public void setValue(T value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  public void reset() {
    value = defaultValue;
  }

  @SuppressWarnings("unchecked")
  public <S extends Setting<T>> S description(String description) {
    this.description = Objects.requireNonNull(description, "description").trim();
    return (S) this;
  }

  @SuppressWarnings("unchecked")
  public <S extends Setting<T>> S unit(String unit) {
    this.unit = Objects.requireNonNull(unit, "unit").trim();
    return (S) this;
  }

  @SuppressWarnings("unchecked")
  public <S extends Setting<T>> S markAdvanced() {
    advanced = true;
    return (S) this;
  }

  public Setting<T> visibleWhen(BoolSetting setting) {
    return visibleWhen(setting, true);
  }

  public Setting<T> visibleWhen(BoolSetting setting, boolean value) {
    visibility = () -> setting.getValue() == value;
    return this;
  }

  public Setting<T> visibleWhen(BooleanSupplier visibility) {
    this.visibility = Objects.requireNonNull(visibility);
    return this;
  }

  public boolean isVisible() {
    return visibility.getAsBoolean();
  }
}
