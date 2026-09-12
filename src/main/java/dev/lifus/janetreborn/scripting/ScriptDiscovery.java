package dev.lifus.janetreborn.scripting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ScriptDiscovery {
  private final Path scriptsDirectory;

  public ScriptDiscovery(Path scriptsDirectory) {
    this.scriptsDirectory = scriptsDirectory.toAbsolutePath().normalize();
  }

  public DiscoveryResult discover() {
    List<ScriptPackage> packages = new ArrayList<>();
    List<ScriptDiagnostic> diagnostics = new ArrayList<>();
    try {
      Files.createDirectories(scriptsDirectory);
      try (var children = Files.list(scriptsDirectory)) {
        children
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(path -> inspect(path, packages, diagnostics));
      }
    } catch (IOException exception) {
      diagnostics.add(
          ScriptDiagnostic.error(
              "discovery", scriptsDirectory, 0, 0, "Cannot scan scripts directory", exception));
    }
    Map<String, ScriptPackage> unique = new LinkedHashMap<>();
    for (ScriptPackage candidate : packages) {
      ScriptPackage existing = unique.putIfAbsent(candidate.manifest().id(), candidate);
      if (existing != null) {
        diagnostics.add(
            ScriptDiagnostic.error(
                candidate.manifest().id(),
                candidate.manifestPath(),
                0,
                0,
                "Duplicate script id; already declared by " + existing.manifestPath(),
                null));
      }
    }
    return new DiscoveryResult(List.copyOf(unique.values()), List.copyOf(diagnostics));
  }

  private void inspect(
      Path path, List<ScriptPackage> packages, List<ScriptDiagnostic> diagnostics) {
    if (!Files.isRegularFile(path)
        || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua")) return;
    try {
      String filename = path.getFileName().toString();
      String stem = filename.substring(0, filename.length() - ".lua".length());
      String id = scriptId(stem);
      ScriptManifest manifest =
          new ScriptManifest(
              1,
              id,
              stem,
              "Lua script " + stem,
              "",
              "1.0.0",
              "^" + JanetSdkVersion.CURRENT,
              filename,
              Set.of(
                  ScriptCapability.CHAT_OUTPUT,
                  ScriptCapability.INVENTORY_ACTIONS,
                  ScriptCapability.CUSTOM_ASSETS),
              List.of(),
              1);
      packages.add(new ScriptPackage(scriptsDirectory, path, manifest));
    } catch (IllegalArgumentException | SecurityException exception) {
      diagnostics.add(
          ScriptDiagnostic.error(
              path.getFileName().toString(), path, 0, 0, exception.getMessage(), exception));
    }
  }

  private static String scriptId(String filename) {
    String id =
        filename
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_.-]+", "-")
            .replaceAll("^[^a-z]+", "")
            .replaceAll("[-_.]+$", "");
    if (id.length() > 64) id = id.substring(0, 64).replaceAll("[-_.]+$", "");
    if (id.length() < 2) {
      throw new IllegalArgumentException(
          "Script filename must produce an id with at least two characters");
    }
    return id;
  }

  public record DiscoveryResult(List<ScriptPackage> packages, List<ScriptDiagnostic> diagnostics) {}
}
