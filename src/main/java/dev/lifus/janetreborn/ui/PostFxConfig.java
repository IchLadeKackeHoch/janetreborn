package dev.lifus.janetreborn.ui;

public interface PostFxConfig {
  default boolean shadersEnabled() {
    return true;
  }

  default boolean blurEnabled() {
    return true;
  }

  default int blurRadius() {
    return 25;
  }

  default float blurResolutionScale() {
    return 0.5f;
  }

  default boolean shadowsEnabled() {
    return true;
  }

  default float shadowSize() {
    return 24.0f;
  }

  default float shadowOpacity() {
    return 0.38f;
  }
}
