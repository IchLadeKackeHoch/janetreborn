package dev.lifus.janetreborn.feature.client;

import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.ModeSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class ClientSettingsModule extends Module {
  private final ModeSetting<ClientFont> font =
      add(new ModeSetting<>("Font", ClientFont.VOLTE_SEMIBOLD));
  private final BoolSetting notifications = add(new BoolSetting("Notifications", true));
  private final NumberSetting<Integer> notificationDuration =
      add(
          new NumberSetting<>("Notification Duration", 3500, 1500, 8000, 250)
              .unit("ms")
              .markAdvanced());
  private final NumberSetting<Integer> uiScale =
      add(
          new NumberSetting<>("UI Scale", 100, 75, 150, 5)
              .unit("%")
              .description("Scales ClickGUI text and controls."));

  public ClientSettingsModule() {
    super("Client", "Configures the Janet Reborn interface", Category.HUD, false);
  }

  public ClientFont getFont() {
    return font.getValue();
  }

  public void resetFont() {
    font.reset();
  }

  public boolean notificationsEnabled() {
    return notifications.getValue();
  }

  public int notificationDurationMillis() {
    return notificationDuration.getValue();
  }

  public float uiScale() {
    return uiScale.getValue() / 100.0f;
  }
}
