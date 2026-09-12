package dev.lifus.janetreborn.feature.client;

import dev.lifus.janetreborn.ui.PostFxConfig;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class PostFxModule extends Module implements PostFxConfig {
  private final BoolSetting blur = add(new BoolSetting("Blur", true));
  private final NumberSetting<Integer> blurRadius =
      add(new NumberSetting<>("Blur Radius", 25, 1, 48, 1).unit("px").markAdvanced());
  private final NumberSetting<Double> blurResolution =
      add(
          new NumberSetting<>("blur_resolution", "Blur Quality", 0.5, 0.25, 1.0, 0.05)
              .description("Render scale used by the blur pass; higher is sharper and slower.")
              .markAdvanced());
  private final BoolSetting shadows = add(new BoolSetting("Shadows", true));
  private final NumberSetting<Integer> shadowSize =
      add(new NumberSetting<>("Shadow Size", 24, 4, 64, 1).unit("px").markAdvanced());
  private final NumberSetting<Integer> shadowOpacity =
      add(new NumberSetting<>("Shadow Opacity", 38, 0, 100, 1).unit("%").markAdvanced());

  public PostFxModule() {
    super("PostProcessing", "Configures interface shaders", Category.VISUAL);
    blurRadius.visibleWhen(blur);
    blurResolution.visibleWhen(blur);
    shadowSize.visibleWhen(shadows);
    shadowOpacity.visibleWhen(shadows);
  }

  @Override
  public boolean shadersEnabled() {
    return isEnabled();
  }

  @Override
  public boolean blurEnabled() {
    return blur.getValue();
  }

  @Override
  public int blurRadius() {
    return blurRadius.getValue();
  }

  @Override
  public float blurResolutionScale() {
    return blurResolution.getValue().floatValue();
  }

  @Override
  public boolean shadowsEnabled() {
    return shadows.getValue();
  }

  @Override
  public float shadowSize() {
    return shadowSize.getValue();
  }

  @Override
  public float shadowOpacity() {
    return shadowOpacity.getValue() / 100.0f;
  }
}
