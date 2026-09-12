package dev.lifus.janetreborn.ui;

import dev.lifus.janetreborn.feature.client.ClientFont;
import dev.lifus.janetreborn.feature.client.ClientSettingsModule;
import imgui.ImFont;
import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class Fonts {
  private final ImGuiImplGl3 renderer;
  private ClientSettingsModule settings;
  private ClientFont loaded;

  Fonts(ImGuiImplGl3 renderer) {
    this.renderer = renderer;
  }

  void setSettings(ClientSettingsModule settings) {
    this.settings = settings;
  }

  void load() {
    ClientFont selected = selected();
    try {
      load(selected);
      loaded = selected;
    } catch (RuntimeException exception) {
      if (selected == ClientFont.CONSOLAS_BOLD) throw exception;
      ImGui.getIO().getFonts().clear();
      settings.resetFont();
      load(ClientFont.CONSOLAS_BOLD);
      loaded = ClientFont.CONSOLAS_BOLD;
    }
  }

  void update() {
    if (selected() == loaded) return;
    renderer.destroyFontsTexture();
    ImGui.getIO().getFonts().clear();
    load();
    if (!renderer.createFontsTexture()) {
      throw new IllegalStateException("Could not rebuild font texture");
    }
  }

  private ClientFont selected() {
    return settings == null ? ClientFont.CONSOLAS_BOLD : settings.getFont();
  }

  private void load(ClientFont selected) {
    Path path = null;
    try (InputStream stream = Fonts.class.getResourceAsStream(selected.resourcePath())) {
      if (stream == null) throw new IllegalStateException("Missing font");
      path = Files.createTempFile("janet-reborn-font", ".ttf");
      Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING);
      ImFont font = ImGui.getIO().getFonts().addFontFromFileTTF(path.toString(), 18);
      if (!ImGui.getIO().getFonts().build() || !font.isLoaded()) {
        throw new IllegalStateException("Invalid font");
      }
      ImGui.getIO().setFontDefault(font);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } finally {
      if (path != null) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      }
    }
  }
}
