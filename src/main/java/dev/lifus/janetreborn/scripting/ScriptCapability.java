package dev.lifus.janetreborn.scripting;

import java.util.Locale;

public enum ScriptCapability {
  CLIPBOARD(true),
  NETWORK(true),
  CHAT_OUTPUT(false),
  INVENTORY_ACTIONS(false),
  CUSTOM_ASSETS(false);

  private final boolean sensitive;

  ScriptCapability(boolean sensitive) {
    this.sensitive = sensitive;
  }

  public boolean isSensitive() {
    return sensitive;
  }

  public static ScriptCapability parse(String value) {
    return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
  }

  public String manifestName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
