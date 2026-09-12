package opsec.misuyaka.sdk;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ModuleInfo(
    String id, String name, String description, Category category, boolean toggleable) {
  private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_.:/-]{0,127}");

  public ModuleInfo {
    id = requireId(id);
    name = requireText(name, "name");
    description = requireText(description, "description");
    Objects.requireNonNull(category, "category");
  }

  public ModuleInfo(String name, String description, Category category, boolean toggleable) {
    this(defaultId(name), name, description, category, toggleable);
  }

  public static ModuleInfo toggleable(String name, String description, Category category) {
    return new ModuleInfo(name, description, category, true);
  }

  public static ModuleInfo toggleable(
      String id, String name, String description, Category category) {
    return new ModuleInfo(id, name, description, category, true);
  }

  public static ModuleInfo internal(String name, String description, Category category) {
    return new ModuleInfo(name, description, category, false);
  }

  private static String requireText(String value, String label) {
    String text = Objects.requireNonNull(value, label).trim();
    if (text.isBlank()) throw new IllegalArgumentException("Module " + label + " cannot be blank");
    return text;
  }

  private static String requireId(String value) {
    String id = Objects.requireNonNull(value, "id").trim().toLowerCase(Locale.ROOT);
    if (!VALID_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("Invalid module id: " + value);
    }
    return id;
  }

  private static String defaultId(String name) {
    String id = requireText(name, "name").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    id = id.replaceAll("^_+|_+$", "");
    return requireId(id);
  }
}
