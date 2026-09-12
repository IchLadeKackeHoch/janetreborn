package dev.lifus.janetreborn.feature.player;

import dev.lifus.janetreborn.service.rotation.Rotations;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class RotationSettingsModule extends Module implements Rotations.Configuration {
  private final NumberSetting<Double> hitVectorVariation =
      add(
          new NumberSetting<>(
                  "hit_vector_variation", "Interaction Point Randomness", 0.06, 0.0, 0.2, 0.01)
              .description("Randomizes the precise point used for block interactions.")
              .unit("blocks")
              .markAdvanced());

  public RotationSettingsModule() {
    super("Rotations", "Configures accurate randomized interaction points", Category.PLAYER, false);
  }

  @Override
  public double hitVectorVariation() {
    return hitVectorVariation.getValue();
  }
}
