package dev.lifus.janetreborn.ui;

import dev.lifus.janetreborn.scripting.ScriptManager;
import dev.lifus.janetreborn.scripting.ScriptModule;
import imgui.ImGui;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

final class ScriptView implements AutoCloseable {
  private static final URI DOCUMENTATION =
      URI.create("https://github.com/lifusdev/janet/blob/main/Scripting.md");

  private final ScriptManager scripts;
  private final ModuleView modules;
  private String status = "Drop a .lua file into the script folder to load it live.";

  ScriptView(ScriptManager scripts, ModuleView modules) {
    this.scripts = scripts;
    this.modules = modules;
  }

  void draw() {
    if (ImGui.button("Open Script Folder")) openScriptFolder();
    ImGui.sameLine();
    if (ImGui.button("View Documentation")) openDocumentation();
    ImGui.sameLine();
    if (ImGui.button("Reload Scripts")) {
      int loaded = scripts.reloadAll();
      status = "Reloaded " + loaded + " script" + (loaded == 1 ? "" : "s");
    }

    ImGui.textDisabled(status);
    ImGui.separator();

    List<ScriptModule> visible =
        scripts.scripts().stream()
            .flatMap(script -> script.modules().stream())
            .sorted(Comparator.comparing(ScriptModule::getName))
            .toList();
    if (visible.isEmpty()) {
      ImGui.textDisabled("No script modules loaded.");
    } else {
      for (ScriptModule module : visible) modules.drawScriptModule(module);
    }

    scripts.scripts().stream()
        .filter(script -> script.latestError() != null)
        .forEach(
            script ->
                ImGui.textColored(
                    1f,
                    0.4f,
                    0.4f,
                    1f,
                    script.scriptPackage().entryPoint().getFileName()
                        + ": "
                        + script.latestError().message()));
  }

  private void openScriptFolder() {
    try {
      Files.createDirectories(scripts.scriptsDirectory());
      Desktop.getDesktop().open(scripts.scriptsDirectory().toFile());
      status = "Opened the script folder";
    } catch (IOException | UnsupportedOperationException exception) {
      status = "Could not open the script folder: " + exception.getMessage();
    }
  }

  private void openDocumentation() {
    try {
      Desktop.getDesktop().browse(DOCUMENTATION);
      status = "Opened Scripting.md";
    } catch (IOException | UnsupportedOperationException exception) {
      status = "Could not open the documentation: " + exception.getMessage();
    }
  }

  @Override
  public void close() {}
}
