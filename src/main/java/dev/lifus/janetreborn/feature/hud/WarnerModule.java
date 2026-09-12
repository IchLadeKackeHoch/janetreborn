package dev.lifus.janetreborn.feature.hud;

import dev.codeman.eventbusx.Listener;
import dev.codeman.eventbusx.Subscribe;
import dev.lifus.janetreborn.client.JanetClient;
import dev.lifus.janetreborn.event.game.FrameRenderEvent;
import dev.lifus.janetreborn.event.game.TickEvent;
import dev.lifus.janetreborn.ui.Notices;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import opsec.misuyaka.sdk.Category;
import opsec.misuyaka.sdk.Module;
import opsec.misuyaka.sdk.setting.BoolSetting;
import opsec.misuyaka.sdk.setting.NumberSetting;

public final class WarnerModule extends Module {
  private static final SoundEvent LOW_HEALTH_SOUND =
      SoundEvent.createVariableRangeEvent(
          Identifier.fromNamespaceAndPath(JanetClient.MOD_ID, "warner_low_health"));
  private final Minecraft minecraft;
  private final Notices notifications;
  private final NumberSetting<Double> threshold =
      add(
          new NumberSetting<>("health_threshold", "Low Health Threshold", 4.5, 0.5, 10.0, 0.5)
              .unit("hearts"));
  private final BoolSetting redEffect = add(new BoolSetting("Red Visual Effect", true));
  private final BoolSetting sound = add(new BoolSetting("Sound Effect", true));
  private final NumberSetting<Integer> soundVolume =
      add(new NumberSetting<>("Sound Volume", 85, 0, 100, 5).unit("%").markAdvanced());
  private boolean danger;

  @Subscribe private final Listener<TickEvent> clientTick = this::checkHealth;
  @Subscribe private final Listener<FrameRenderEvent> frameRender = this::renderWarning;

  public WarnerModule(Minecraft minecraft, Notices notifications) {
    super("Warner", "Warns you with a red pulse and sound when health is low", Category.HUD);
    this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    soundVolume.visibleWhen(sound);
  }

  @Override
  protected void onDisable() {
    danger = false;
  }

  private void checkHealth(TickEvent ignored) {
    if (minecraft.player == null || !minecraft.player.isAlive()) {
      danger = false;
      return;
    }
    float hearts = minecraft.player.getHealth() * 0.5f;
    boolean low = hearts <= threshold.getValue();
    if (low && !danger) {
      danger = true;
      if (sound.getValue()) {
        minecraft
            .getSoundManager()
            .play(
                SimpleSoundInstance.forUI(LOW_HEALTH_SOUND, 1.0f, soundVolume.getValue() / 100.0f));
      }
      notifications.hud(
          "Warner",
          String.format(Locale.ROOT, "%.1f hearts remaining", hearts),
          Notices.Type.ERROR);
    } else if (!low) {
      danger = false;
    }
  }

  private void renderWarning(FrameRenderEvent ignored) {
    if (!danger || !redEffect.getValue() || minecraft.player == null) return;
    float width = ImGui.getIO().getDisplaySizeX();
    float height = ImGui.getIO().getDisplaySizeY();
    float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 180_000_000.0);
    int wash = ImColor.rgba(190, 0, 0, Math.round(18 + pulse * 20));
    int border = ImColor.rgba(255, 30, 30, Math.round(135 + pulse * 100));
    ImDrawList draw = ImGui.getForegroundDrawList();
    draw.addRectFilled(0, 0, width, height, wash);
    draw.addRect(4, 4, width - 4, height - 4, border, 0, 0, 7);
  }
}
