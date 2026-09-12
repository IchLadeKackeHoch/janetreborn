package dev.lifus.janetreborn.feature.client;

public enum ClientFont {
  CONSOLAS_BOLD("Consolas Bold", "Consolas-Bold.ttf"),
  JETBRAINS_MONO_BOLD("JetBrains Mono Bold", "JetBrainsMono-Bold.ttf"),
  MINECRAFT("Minecraft", "Minecraft.ttf"),
  SF_PRO_DISPLAY_SEMIBOLD("SF Pro Display Semibold", "SFProDisplay-Semibold.ttf"),
  UBUNTU_BOLD("Ubuntu Bold", "Ubuntu-Bold.ttf"),
  VOLTE_SEMIBOLD("Volte Semibold", "Volte-Semibold.ttf");

  private final String displayName;
  private final String fileName;

  ClientFont(String displayName, String fileName) {
    this.displayName = displayName;
    this.fileName = fileName;
  }

  public String fileName() {
    return fileName;
  }

  public String resourcePath() {
    return "/fonts/" + fileName;
  }

  @Override
  public String toString() {
    return displayName;
  }
}
