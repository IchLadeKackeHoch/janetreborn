package dev.lifus.janetreborn.ui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import java.util.List;
import java.util.Objects;

public final class NoticeView {
  private static final int FLAGS =
      ImGuiWindowFlags.NoDecoration
          | ImGuiWindowFlags.AlwaysAutoResize
          | ImGuiWindowFlags.NoSavedSettings
          | ImGuiWindowFlags.NoFocusOnAppearing
          | ImGuiWindowFlags.NoNav
          | ImGuiWindowFlags.NoInputs;
  private final Notices notifications;
  private final UiEffects effects;

  public NoticeView(Notices notifications, UiEffects effects) {
    this.notifications = Objects.requireNonNull(notifications, "notifications");
    this.effects = Objects.requireNonNull(effects, "effects");
  }

  public void render(boolean menuOpen, boolean enabled) {
    if (!enabled) {
      notifications.visible(Notices.Channel.HUD);
      notifications.visible(Notices.Channel.GUI);
      return;
    }
    draw(notifications.visible(Notices.Channel.HUD), false, true);
    if (menuOpen) draw(notifications.visible(Notices.Channel.GUI), true, true);
  }

  public boolean hasVisibleNotifications(boolean menuOpen) {
    return notifications.hasVisible(menuOpen);
  }

  public void setDisplayMillis(int milliseconds) {
    notifications.setDisplayMillis(milliseconds);
  }

  private void draw(
      List<Notices.Notification> items, boolean guiPopup, boolean applyClickGuiEffects) {
    float displayWidth = ImGui.getIO().getDisplaySizeX();
    float displayHeight = ImGui.getIO().getDisplaySizeY();
    long now = System.nanoTime();
    for (int i = 0; i < items.size(); i++) {
      Notices.Notification item = items.get(i);
      float age = Math.max(0, (now - item.createdAt()) / 1_000_000_000.0f);
      float remaining = Math.max(0, (item.expiresAt() - now) / 1_000_000_000.0f);
      float alpha = Math.min(1, age / 0.18f) * Math.min(1, remaining / 0.35f);
      float slide = (1 - alpha) * 32;
      float y = guiPopup ? displayHeight - 22 - i * 66 : 22 + i * 66;
      ImGui.setNextWindowPos(displayWidth - 18 + slide, y, ImGuiCond.Always, 1, guiPopup ? 1 : 0);
      if (ImGui.begin("##JanetNotification" + item.id(), FLAGS)) {
        if (applyClickGuiEffects)
          effects.drawCurrentWindow(
              guiPopup ? UiEffects.Surface.POPUP : UiEffects.Surface.HUD, alpha);
        ImGui.textColored(
            item.type().red(), item.type().green(), item.type().blue(), alpha, item.title());
        ImGui.textColored(0.92f, 0.94f, 1.0f, alpha, item.message());
      }
      ImGui.end();
    }
  }
}
